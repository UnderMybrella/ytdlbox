package dev.brella.ytdlbox

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.response.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onErrorResume
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File

class WebsocketControl(val box: YtdlBox, val session: WebSocketServerSession, val format: SerialFormat) : WebSocketServerSession by session {
    val logger = LoggerFactory.getLogger("WebsocketControl")

    val incomingJob = when (format) {
        is StringFormat -> incoming.receiveAsFlow()
            .filterIsInstance<Frame.Text>()
            .onEach { frame -> receiveRequest(format.decodeFromString(frame.readText())) }
            .launchIn(this)

        is BinaryFormat -> incoming.receiveAsFlow()
            .filterIsInstance<Frame.Binary>()
            .onEach { frame -> receiveRequest(format.decodeFromByteArray(frame.readBytes())) }
            .launchIn(this)

        else -> error("Unknown SerialFormat $format (${format::class})")
    }

    val outgoingChannel = Channel<WebsocketResponse>(16)
    val outgoingJob = when (format) {
        is StringFormat -> outgoingChannel.receiveAsFlow()
            .catch { th -> th.printStackTrace() }
            .onEach { logger.trace("[{}] Sending {}", it.nonce, it::class.simpleName) }
            .onEach { response -> send(format.encodeToString(response)); flush() }
            .launchIn(this)

        is BinaryFormat -> outgoingChannel.receiveAsFlow()
            .catch { th -> th.printStackTrace() }
            .onEach { logger.trace("[{}] Sending {}", it.nonce, it::class.simpleName) }
            .onEach { response -> send(format.encodeToByteArray(response)); flush() }
            .launchIn(this)

        else -> error("Unknown SerialFormat $format (${format::class})")
    }

    suspend inline fun send(response: WebsocketResponse) =
        outgoingChannel.send(response)


    suspend fun receiveRequest(request: WebsocketRequest) {
        logger.debug("[{}] {}: /{}", request.nonce, session.call.request.origin, request::class.simpleName)

        when (request) {
            is WebsocketRequest.AddProxyServer ->
                if (box.proxyListener?.addProxy(request.proxy) != null)
                    send(WebsocketResponse.AddedProxyServer(request.nonce, request.proxy))
                else
                    send(WebsocketResponse.NoProxyListener(request.nonce))

            is WebsocketRequest.RemoveProxyServer ->
                if (box.proxyListener != null)
                    send(WebsocketResponse.RemovedProxyServer(request.nonce, box.proxyListener.removeProxy(request.address)))
                else
                    send(WebsocketResponse.NoProxyListener(request.nonce))

            is WebsocketRequest.Download -> {
                val existingTask = box.incomingUrls[request.request.url]?.let(box.ongoingTasks::get)
                val nonce = request.nonce
                val listenFor = request.listenFor

                if (existingTask != null) {
                    if (existingTask.status.isComplete) {
                        send(WebsocketResponse.Downloading(nonce, existingTask.taskID, false, existingTask.url, existingTask.parameters))
                        if (listenFor != ListenCondition.DO_NOT_LISTEN) onCompletion(nonce, existingTask, box.logFileForTask(existingTask.taskID), box.outputFileForTask(existingTask.taskID), listenFor)
                    } else {
                        if (listenFor != ListenCondition.DO_NOT_LISTEN) existingTask.onComplete.add { completed, logFile, outputFile -> onCompletion(nonce, completed, logFile, outputFile, listenFor) }
                        send(WebsocketResponse.Downloading(nonce, existingTask.taskID, false, existingTask.url, existingTask.parameters))
                    }
                } else {
                    val process = OngoingProcess.beginDownloadFor(box, request.request.url, request.request.args, request.request.completionActions)
                    if (listenFor != ListenCondition.DO_NOT_LISTEN) process.onComplete.add { completed, logFile, outputFile -> onCompletion(nonce, completed, logFile, outputFile, listenFor) }

                    send(WebsocketResponse.Downloading(nonce, process.taskID, true, process.url, process.parameters))
                }
            }

            is WebsocketRequest.GetServerInfo -> send(
                WebsocketResponse.ServerInfo(
                    request.nonce,
                    YtdlBoxFeatureSet(
                        ytdlProcess = box.ytdlProcess,
                        defaultArgs = box.ytdlArgs,
                        rotatingIpv6 = box.rotateAmongIPv6 != null,
                        listeningForProxy = box.proxyListener != null,
                        completionActions = box.actionConfigs.values
                            .mapNotNull(CompletionActionType.CompletionActionConfig::buildFeatureSet)
                    )
                )
            )

            is WebsocketRequest.GetTaskInfo -> {
                val taskID = request.taskID
                val ongoing = box.ongoingTasks[taskID]
                              ?: return send(WebsocketResponse.NoTaskWithID(request.nonce, taskID))

                val logTail = box.logFileForTask(taskID)
                    .takeIf(File::exists)
                    ?.useLines { it.lastOrNull() }

                send(
                    WebsocketResponse.TaskInfo(
                        request.nonce,
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
                )
            }

            is WebsocketRequest.GetTaskLogs -> {
                val logFile = box.logFileForTask(request.taskID)

                send(
                    WebsocketResponse.TaskLogs(
                        request.nonce,
                        if (logFile.exists()) logFile.readLines() else emptyList()
                    )
                )
            }

            is WebsocketRequest.GetTaskDownload -> {
                val outputFile = box.outputFileForTask(request.taskID)

                if (outputFile?.exists() == true) {
                    send(WebsocketResponse.TaskDownload(request.nonce, outputFile.readBytes(), ContentType.defaultForFile(outputFile).toString()))
                } else {
                    send(WebsocketResponse.NoDownloadForTaskAvailable(request.nonce, request.taskID))
                }
            }
        }
    }

//    suspend (process: OngoingProcess, logFile: File, outputFile: File?) -> Unit

    suspend fun onCompletion(nonce: Long, process: OngoingProcess, logsFile: File, outputFile: File?, listenFor: ListenCondition) {
        logger.debug("[{}] Running completion handler for {}: {}", nonce, process.taskID, process.status)

        if (process.status == ProcessStatus.COMPLETE_SUCCESS) {
            val logs = logsFile
                .takeIf(File::exists)
                ?.useLines { it.toList() }

            val output = if (listenFor == ListenCondition.LISTEN_WITH_DATA)
                outputFile
                    ?.takeIf(File::exists)
                    ?.readBytes()
            else
                null

            val mimeType = outputFile
                ?.let(ContentType::defaultForFile)

            send(
                WebsocketResponse.DownloadSuccess(
                    nonce,
                    process.taskID,
                    process.url,
                    process.commandLine,
                    output ?: ByteArray(0),
                    mimeType?.toString(),
                    logs ?: emptyList()
                )
            )
        } else {
            val logs = logsFile
                .takeIf(File::exists)
                ?.useLines { it.toList() }

            send(
                WebsocketResponse.DownloadFailure(
                    nonce,
                    process.taskID,
                    process.url,
                    process.commandLine,
                    logs ?: emptyList()
                )
            )
        }
    }
}