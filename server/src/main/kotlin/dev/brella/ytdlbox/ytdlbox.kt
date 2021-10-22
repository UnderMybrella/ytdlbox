package dev.brella.ytdlbox

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.event.Level
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.io.File
import java.time.Duration
import java.util.*
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

inline fun CoroutineScope.OngoingProcess(taskID: String, url: String, parameters: List<String>, crossinline block: suspend CoroutineScope.(OngoingProcess) -> Unit) =
    OngoingProcess.invoke(this, taskID, url, parameters, block)

class YtdlBox(val application: Application) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    val ongoingTasks: MutableMap<String, OngoingProcess> = HashMap()
    val incomingUrls: MutableMap<String, String> = HashMap()

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

    val proxyListener = if (applicationConfig
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

    fun logFileForTask(taskID: String): File =
        File(logsDir, "$taskID.log")

    fun outputTemplateForTask(taskID: String): String =
        "${holdingDir.absolutePath}/${taskID}.%(ext)s"

    fun outputFileForTask(taskID: String): File? =
        holdingDir.listFiles()
            ?.firstOrNull { file -> file.nameWithoutExtension == taskID }

    fun beginDownload(url: String, options: List<String>): OngoingProcess =
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

                            println("--Using $ip")

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
                    if (!ongoing.onFailure.fold(true) { acc, func -> acc && func(ongoing, logFile) }) break
                } catch (th: Throwable) {
                    th.printStackTrace()
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
                if (successful && outputFile?.exists() == true) ongoing.onSuccess.forEach { it(ongoing, logFile, outputFile) }
                ongoing.onComplete.forEach { it(ongoing, logFile, outputFile) }

                delay(120_000)

                ongoing.status = ProcessStatus.SHUTTING_DOWN

                delay(60_000)

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

                    val ongoing = ongoingTasks[taskID] ?: return@get call.respond(
                        HttpStatusCode.NotFound, WebError(taskID, "No task with ID $taskID")
                    )

                    val logTail = logFileForTask(taskID)
                        .takeIf(File::exists)
                        ?.useLines { it.lastOrNull() }

                    call.respond(
                        when (ongoing.status) {
                            ProcessStatus.RUNNING -> {
                                var processInfo: TaskInfo.Running.ProcessHandleInfo? = null
                                var processError: String? = null

                                try {
                                    val process = ongoing.process.toHandle()
                                    val info = process.info()

                                    processInfo = TaskInfo.Running.ProcessHandleInfo(
                                        pid = process.pid(),
                                        command = info.command().orElse(null),
                                        arguments = info.arguments()?.orElse(null)?.toList() ?: emptyList(),
                                        commandLine = info.commandLine().orElse(null),
                                        startTime = info.startInstant().orElse(null)?.toString()
                                    )
                                } catch (uoe: UnsupportedOperationException) {
                                    processInfo = null
                                    processError = uoe.stackTraceToString()
                                }

                                TaskInfo.Running(
                                    ongoing.taskID,
                                    ongoing.url,
                                    ongoing.parameters,
                                    ongoing.commandLine,
                                    "/${ongoing.taskID}/logs",
                                    ongoing.status,
                                    logTail,
                                    processInfo,
                                    processError
                                )
                            }
                            ProcessStatus.COMPLETE_SUCCESS ->
                                TaskInfo.Successful(
                                    ongoing.taskID,
                                    ongoing.url,
                                    ongoing.parameters,
                                    ongoing.commandLine,
                                    "/${ongoing.taskID}/logs",
                                    ongoing.status,
                                    logTail,
                                    "/${ongoing.taskID}/download"
                                )

                            else -> TaskInfo.Generic(
                                ongoing.taskID,
                                ongoing.url,
                                ongoing.parameters,
                                ongoing.commandLine,
                                "/${ongoing.taskID}/logs",
                                ongoing.status,
                                logTail
                            )
                        }
                    )
                }
                get("/log", "/logs") {
                    val taskID = call.parameters.getOrFail("task_id")

                    val logFile = logFileForTask(taskID)
                    if (logFile.exists()) {
//                        val json = call.request.queryParameters["json"] != null ||
//                                   call.request.queryParameters["format"] == "json" ||
//                                   call.request.acceptItems().firstOrNull()?.value?.contains("application/json") == true

                        call.respond(logFile.useLines { seq -> seq.toList() })
                    } else {
                        call.respond(HttpStatusCode.NotFound, WebError(taskID, "No logs found for $taskID"))
                    }
                }
                get("/download") {
                    val taskID = call.parameters.getOrFail("task_id")

                    val outputFile = outputFileForTask(taskID)

                    if (outputFile?.exists() == true) {
                        call.respondFile(outputFile)
                    } else {
                        call.respond(HttpStatusCode.NotFound, WebError(taskID, "No download found for $taskID"))
                    }
                }
            }

            post("/download") { request: DownloadRequest ->
                val existingTask = incomingUrls[request.url]?.let(ongoingTasks::get)
                if (existingTask != null) {
                    return@post call.respond(DownloadResponse(existingTask.taskID, false, existingTask.url, existingTask.parameters))
                } else {
                    return@post call.respond(HttpStatusCode.Created, DownloadResponse(beginDownload(request.url, request.args).taskID, true, request.url, request.args))
                }
            }
            webSocket("/connect") {
                println("--Start")
                val acceptHeaderContent = call.request.header(HttpHeaders.Accept)
                val acceptHeader = try {
                    parseHeaderValue(acceptHeaderContent)
                        .map { ContentTypeWithQuality(ContentType.parse(it.value), it.quality) }
                        .distinct()
                        .sortedByQuality()
                } catch (parseFailure: BadContentTypeFormatException) {
                    throw BadRequestException(
                        "Illegal Accept header format: $acceptHeaderContent",
                        parseFailure
                    )
                }

                println("--Parsed")
                var format: SerialFormat? = null

                for ((contentType) in acceptHeader) {
                    when {
                        ContentType.Application.Json.match(contentType) -> {
                            format = Json.Default
                            break
                        }
                        ContentType.Application.Cbor.match(contentType) -> {
                            format = Cbor.Default
                            break
                        }
                        ContentType.Application.ProtoBuf.match(contentType) -> {
                            format = ProtoBuf.Default
                            break
                        }
                    }
                }

                println("--Decided on $format")

                WebsocketControl(this@YtdlBox, this, format ?: Json)
                    .incomingJob
                    .join()
            }

            if (proxyListener != null) {
                route("/proxy") {
                    post("/add") { proxy: DownloadProxy ->
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
        application.install(CallLogging) {
            level = Level.INFO
            filter { call -> call.request.path().startsWith("/") }
        }
        application.intercept(ApplicationCallPipeline.Setup) {
            println(call.request.headers.flattenEntries().joinToString("\n") { (k, v) -> "$k: $v" })
        }

        application.install(ContentNegotiation) {
            json()
            serialization(ContentType.Application.Cbor, Cbor.Default)
            serialization(ContentType.Application.ProtoBuf, ProtoBuf.Default)
        }
        application.install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
//        application.install(CORS) {
//            methods.addAll(HttpMethod.DefaultMethods)
//            allowCredentials = true
//            anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
//        }
        application.routing(this::setupRouting)

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            logsDir.deleteRecursively()
            holdingDir.deleteRecursively()
        })
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() = YtdlBox(this)