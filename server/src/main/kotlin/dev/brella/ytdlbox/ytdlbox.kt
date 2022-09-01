package dev.brella.ytdlbox

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.io.File
import java.time.Duration
import java.util.*
import java.util.regex.Pattern
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class YtdlBox(val application: Application) : CoroutineScope {
    companion object {
        val STRING_SPLIT = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")

        @OptIn(ExperimentalStdlibApi::class)
        public fun String.splitParams(): List<String> = buildList {
            val regexMatcher = STRING_SPLIT.matcher(this@splitParams)
            while (regexMatcher.find()) {
                regexMatcher.group(1)?.let(::add) // Add double-quoted string without the quotes
                ?: regexMatcher.group(2)?.let(::add) // Add single-quoted string without the quotes
                ?: add(regexMatcher.group()) // Add unquoted word
            }
        }
    }

    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    val ongoingTasks: MutableMap<String, OngoingProcess> = HashMap()
    val incomingUrls: MutableMap<String, String> = HashMap()

    val logger = LoggerFactory.getLogger("YtdlBox")

    inline fun generateTaskID(): String = UUID.randomUUID().toString()

    internal val applicationConfig = application
        .environment
        .config
        .config("ytdlbox")

    val ytdlProcess =
        applicationConfig
            .propertyOrNull("youtube-dl")
            ?.getString()
        ?: if (System.getProperty("os.name").contains("win", true))
            "youtube-dl.exe"
        else
            "youtube-dl"

    val ytdlArgs =
        System.getenv("BOX_YTDL_ARGS")
            ?.splitParams()
        ?: applicationConfig
            .propertyOrNull("args")
            ?.getList()
        ?: emptyList()

    val actionConfigs: Map<CompletionActionType, CompletionActionType.CompletionActionConfig> =
        CompletionActionType
            .values()
            .mapNotNull { config -> config(this)?.let(config::to) }
            .toMap()

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

    internal val rotateAmongIPv6 = applicationConfig
        .propertyOrNull("rotate_among_ipv6")
        ?.getString()

    val sourceAddresses = rotateAmongIPv6
        ?.let(::IPAddressString)
        ?.address?.let { base ->
            produce<IPAddress> {
                var seqBlocks: Iterator<IPAddress> = object : Iterator<IPAddress> {
                    override fun next(): IPAddress = throw IllegalStateException("Cannot call next on empty")
                    override fun hasNext(): Boolean = false
                }

                while (isActive) {
                    if (!seqBlocks.hasNext()) {
                        logger.info("Refreshing SeqBlocks")
                        seqBlocks = base.sequentialBlockIterator()
                    }

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

            get("/info") {
                call.respond(YtdlBoxFeatureSet(
                    ytdlProcess = ytdlProcess,
                    defaultArgs = ytdlArgs,
                    rotatingIpv6 = rotateAmongIPv6 != null,
                    listeningForProxy = proxyListener != null,
                    completionActions = actionConfigs.values
                        .mapNotNull(CompletionActionType.CompletionActionConfig::buildFeatureSet)
                ))
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
                val key = request.key()
                val existingTask = incomingUrls[key]?.let(ongoingTasks::get)
                if (existingTask != null) {
                    return@post call.respond(DownloadResponse(existingTask.taskID, false, existingTask.url, existingTask.parameters))
                } else {
                    return@post call.respond(HttpStatusCode.Created, DownloadResponse(OngoingProcess.beginDownloadFor(this@YtdlBox, request.url, request.args, request.completionActions, key).taskID, true, request.url, request.args))
                }
            }
            webSocket("/connect") {
                val acceptHeaderContent = call.request.header(HttpHeaders.Accept)
                val acceptHeader = try {
                    parseAndSortContentTypeHeader(acceptHeaderContent)
                } catch (parseFailure: BadContentTypeFormatException) {
                    throw BadRequestException(
                        "Illegal Accept header format: $acceptHeaderContent",
                        parseFailure
                    )
                }

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
//        application.install(CallLogging) {
//            level = Level.INFO
//            filter { call -> call.request.path().startsWith("/") }
//        }
//        application.intercept(ApplicationCallPipeline.Setup) {
//            println(call.request.headers.flattenEntries().joinToString("\n") { (k, v) -> "$k: $v" })
//        }

        application.install(ContentNegotiation) {
            serialization(ContentType.Application.Json, Json.Default)
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