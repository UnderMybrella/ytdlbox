package dev.brella.ytdlbox

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.awt.MediaTracker.COMPLETE
import java.io.File
import java.util.*
import kotlin.collections.HashMap
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun interface TradeOffer<I, O> {
    public suspend fun complete(input: I): O
}

class TradeOfferImpl<I, O>(val incoming: CompletableDeferred<I>, val outgoing: Deferred<O>) : TradeOffer<I, O> {
    override suspend fun complete(input: I): O {
        incoming.complete(input)
        return outgoing.await()
    }
}

@Serializable
data class DownloadRequest(val url: String, val args: List<String> = emptyList())

inline fun <T> CoroutineScope.launchLazyWithParam(
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend CoroutineScope.(T) -> Unit
): TradeOffer<T, Job> {
    val incoming = CompletableDeferred<T>(context[Job])
    val job = launch(context = context, start = CoroutineStart.LAZY) { block(incoming.await()) }

    return TradeOffer {
        incoming.complete(it)
        job.start()
        return@TradeOffer job
    }
}

inline fun CoroutineScope.OngoingProcess(taskID: String, url: String, parameters: List<String>, crossinline block: suspend CoroutineScope.(OngoingProcess) -> Unit) =
    OngoingProcess.invoke(this, taskID, url, parameters, block)

class YtdlBox(val application: Application) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    private val ongoingTasks: MutableMap<String, OngoingProcess> = HashMap()
    private val incomingUrls: MutableMap<String, String> = HashMap()

    private inline fun generateTaskID(): String = UUID.randomUUID().toString()

    private val applicationConfig = application
        .environment
        .config
        .config("ytdlbox")

    private val ytdlProcess = applicationConfig
                                  .propertyOrNull("youtube-dl")
                                  ?.getString() ?: if (System.getProperty("os.name").contains("win", true)) "youtube-dl.exe" else "youtube-dl"

    private val ytdlArgs = applicationConfig
                               .propertyOrNull("args")
                               ?.getList() ?: emptyList()

    private val argon2 = Argon2PasswordEncoder()
    private val ytdlAuth = applicationConfig
        .property("auth")
        .getString()
        .let { str ->
            if (!str.startsWith("\$argon2")) {
                val encoded = argon2.encode(str)
                println("==WARNING==")
                println("Password stored in cleartext; store instead as '$encoded'")

                return@let encoded
            } else {
                return@let str
            }
        }

    private val rotateAmongIPv6 = applicationConfig
        .propertyOrNull("rotate_among_ipv6")
        ?.getString()

    private val sourceAddresses = rotateAmongIPv6
        ?.let(::IPAddressString)
        ?.address?.let { base ->
            produce<IPAddress> {
                var seqBlocks: Iterator<IPAddress> = object : Iterator<IPAddress> {
                    override fun next(): IPAddress = throw IllegalStateException("Cannot call next on empty")
                    override fun hasNext(): Boolean = false
                }

                while (isActive) {
                    if (!seqBlocks.hasNext()) seqBlocks = base.sequentialBlockIterator()

                    seqBlocks.next()
                        .toSequentialRange()
                        .iterator()
                        .forEach { addr -> send(addr) }
                }
            }
        }

    private val proxyListener = if (applicationConfig
            .propertyOrNull("listen_for_proxy")
            ?.getString()
            ?.toBooleanStrictOrNull() == true
    ) ProxyListener(this) else null

    private val logsDir = File(
        applicationConfig
            .propertyOrNull("logs_dir")
            ?.getString() ?: "logs"
    ).also(File::mkdirs)

    private val holdingDir = File(
        applicationConfig
            .propertyOrNull("holding_dir")
            ?.getString() ?: "holding"
    ).also(File::mkdirs)

    private fun logFileForTask(taskID: String): File =
        File(logsDir, "$taskID.log")

    private fun outputTemplateForTask(taskID: String): String =
        "${holdingDir.absolutePath}/${taskID}.%(ext)s"

    private fun outputFileForTask(taskID: String): File? =
        holdingDir.listFiles()
            ?.firstOrNull { file -> file.nameWithoutExtension == taskID }

    private fun beginDownload(url: String, options: List<String>): OngoingProcess =
        OngoingProcess(generateTaskID(), url, options) { ongoing ->
            ongoing.status = ProcessStatus.INITIALISING

            val logFile = logFileForTask(ongoing.taskID)
            val holdingTemplate = outputTemplateForTask(ongoing.taskID)

            var successful: Boolean = false

            for (i in 0 until 5) {
                val proxy = proxyListener?.borrowProxy()
                try {
                    val fullCommandLine = ArrayList<String>().apply {
                        add(ytdlProcess)
                        addAll(ytdlArgs)
                        addAll(options)

                        if (proxy != null) {
                            add("--proxy")
                            add(proxy.address)
                        } else if (sourceAddresses?.isClosedForReceive == false) {
                            val ip = sourceAddresses.receive()

                            if (ip.isIPv6) add("--force-ipv6")
                            else if (ip.isIPv6) add("--force-ipv4")

                            add("--source-address")
                            add(ip.toCanonicalString())
                        }

                        add("-o")
                        add(holdingTemplate)

                        add(ongoing.url)
                    }

                    ongoing.commandLine = fullCommandLine
                    ongoing.process = ProcessBuilder(fullCommandLine)
                        .redirectOutput(logFile)
                        .redirectErrorStream(true)
                        .start()

                    ongoing.status = ProcessStatus.RUNNING

                    try {
                        ongoing.process
                            .toHandle()
                            .onExit()
                            .await()
                    } catch (uoe: UnsupportedOperationException) {
                        ongoing.process.waitFor()
                    }

                    successful = ongoing.process.exitValue() <= 0
                    if (successful) break
                    if (ongoing.onFailure?.invoke(logFile) == false) break
                } finally {
                    if (proxy != null) {
                        var proxyError: Boolean = false

                        if (logFile.exists()) {
                            logFile.useLines { seq ->
                                seq.forEach { line ->
                                    if (line.contains("ERROR", true) && line.contains("forcibly closed by the remote host", true)) {
                                        println("Warning $proxy due to $line")

                                        proxyError = true
                                        return@useLines
                                    }
                                }
                            }
                        }

                        proxyListener!!.returnProxy(proxy, proxyError)
                    }
                }
            }

            ongoing.status = if (successful) ProcessStatus.COMPLETE_SUCCESS else ProcessStatus.COMPLETE_FAILURE
            val outputFile = outputFileForTask(ongoing.taskID)

            try {
                if (successful && outputFile?.exists() == true) ongoing.onSuccess?.invoke(logFile, outputFile)
                ongoing.onComplete?.invoke(logFile, outputFile)

                delay(120_000)

                ongoingTasks.remove(ongoing.taskID)
                incomingUrls.remove(ongoing.url)
            } finally {
                logFile.delete()
                outputFile?.delete()
            }

//            logFileForTask(ongoing.taskID).delete()
//            outputFileForTask(ongoing.taskID)?.delete()
        }.also { process ->
            ongoingTasks[process.taskID] = process
            incomingUrls[process.url] = process.taskID
        }

    private suspend inline fun PipelineContext<Unit, ApplicationCall>.isAllowed(): Boolean {
        val existingAuth = call.request.header(HttpHeaders.Authorization) ?: return false

        return argon2.matches(existingAuth, ytdlAuth)
    }

    fun setupRouting(routing: Routing) =
        with(routing) {
            intercept(ApplicationCallPipeline.Features) {
                if (isAllowed()) {
                    proceed()
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                    finish()
                }
            }

            route("/{task_id}") {
                get("/", "/info") {
                    val taskID = call.parameters.getOrFail("task_id")

                    val ongoing = ongoingTasks[taskID]
                                  ?: return@get call.respondJsonObject(status = HttpStatusCode.NotFound) {
                                      put("error", "No task with ID $taskID")
                                  }

                    call.respondJsonObject {
                        put("task_id", ongoing.taskID)
                        put("url", ongoing.url)
                        put("args", JsonArray(ongoing.parameters.map(::JsonPrimitive)))
                        put("command_line", JsonArray(ongoing.commandLine.map(::JsonPrimitive)))
                        put("logs_location", "/$taskID/logs")
                        put("status", ongoing.status.name)

                        val logFile = File(logsDir, "${ongoing.taskID}.log")
                        if (logFile.exists()) {
                            logFile.useLines { seq ->
                                val lines = seq.mapTo(ArrayList(), ::JsonPrimitive)
                                put("log_tail", lines.lastOrNull() ?: JsonNull)
                                put("log", JsonArray(lines))
                            }
                        }

                        when (ongoing.status) {
                            ProcessStatus.INITIALISING -> {}
                            ProcessStatus.RUNNING -> {
                                putJsonObject("process") {
                                    try {
                                        val process = ongoing.process.toHandle()
                                        val info = process.info()
                                        put("pid", process.pid())
                                        put("command", info.command().orElse(null))
                                        put("arguments", JsonArray(info.arguments().orElse(null)?.map(::JsonPrimitive) ?: emptyList()))
                                        put("command_line", info.commandLine().orElse(null))
                                        put("start_instant", info.startInstant().orElse(null).toString())
                                    } catch (uoe: UnsupportedOperationException) {
                                        put("error", uoe.stackTraceToString())
                                    }
                                }
                            }
                            ProcessStatus.COMPLETE_SUCCESS -> put("data", "/$taskID/download")
                        }
                    }
                }
                get("/log", "/logs") {
                    val taskID = call.parameters.getOrFail("task_id")

                    val logFile = logFileForTask(taskID)
                    if (logFile.exists()) {
                        val json = call.request.queryParameters["json"] != null ||
                                   call.request.queryParameters["format"] == "json" ||
                                   call.request.acceptItems().firstOrNull()?.value?.contains("application/json") == true

                        if (json) {
                            logFile.useLines { seq ->
                                call.respondText(JsonArray(seq.mapTo(ArrayList(), ::JsonPrimitive)).toString())
                            }
                        } else {
                            logFile.useLines { seq ->
                                call.respondText(seq.joinToString("\n"))
                            }
                        }
                    }
                }
                get("/download") {
                    val taskID = call.parameters.getOrFail("task_id")

                    val outputFile = outputFileForTask(taskID)

                    if (outputFile?.exists() == true) {
                        call.respondFile(outputFile)
                    } else {
                        call.respondJsonObject(status = HttpStatusCode.NotFound) {
                            put("task", taskID)
                            put("error", "No download found for $taskID")
                        }
                    }
                }
            }

            post("/download") { request: DownloadRequest ->
                val existingID = incomingUrls[request.url]
                if (existingID != null) {
                    return@post call.respondJsonObject(status = HttpStatusCode.OK) {
                        put("id", existingID)
                        put("created", false)
                        put("url", request.url)
                        put("args", JsonArray(request.args.map(::JsonPrimitive)))
                    }
                } else {
                    return@post call.respondJsonObject(status = HttpStatusCode.Created) {
                        put("id", beginDownload(request.url, request.args).taskID)
                        put("created", true)
                        put("url", request.url)
                        put("args", JsonArray(request.args.map(::JsonPrimitive)))
                    }
                }
            }

            if (proxyListener != null) {
                route("/proxy") {
                    post("/add") { proxy: DLProxy ->
                        proxyListener.addProxy(proxy)
                        call.respond(HttpStatusCode.Created)
                    }

                    post("/delete") { address: String ->
                        call.respond(proxyListener.removeProxy(address) ?: Unit)
                    }
                }
            }
        }

    init {
        application.install(ContentNegotiation) {
            json()
        }
        application.routing(this::setupRouting)

        logsDir.deleteRecursively()
        holdingDir.deleteRecursively()

        Runtime.getRuntime().addShutdownHook(thread {
            logsDir.deleteRecursively()
            holdingDir.deleteRecursively()
        })
    }
}

fun Application.module() = YtdlBox(this)