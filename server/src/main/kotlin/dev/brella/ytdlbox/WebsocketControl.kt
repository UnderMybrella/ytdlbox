package dev.brella.ytdlbox

import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
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
        logger.info("[{}] {}: /{}", request.nonce, session.call.request.origin, request::class.simpleName)

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

                if (existingTask != null) {
                    if (existingTask.status.isComplete) {
                        send(WebsocketResponse.Downloading(nonce, existingTask.taskID, false, existingTask.url, existingTask.parameters))
                        onCompletion(nonce, existingTask, box.logFileForTask(existingTask.taskID), box.outputFileForTask(existingTask.taskID))
                    } else {
                        if (request.listenFor) existingTask.onComplete.add { completed, logFile, outputFile -> onCompletion(nonce, completed, logFile, outputFile) }
                        send(WebsocketResponse.Downloading(nonce, existingTask.taskID, false, existingTask.url, existingTask.parameters))
                    }
                } else {
                    val process = OngoingProcess.beginDownloadFor(box, request.request.url, request.request.args)
                    if (request.listenFor) process.onComplete.add { completed, logFile, outputFile -> onCompletion(nonce, completed, logFile, outputFile) }

                    send(WebsocketResponse.Downloading(nonce, process.taskID, true, process.url, process.parameters))
                }
            }
        }
    }

//    suspend (process: OngoingProcess, logFile: File, outputFile: File?) -> Unit

    suspend fun onCompletion(nonce: Long, process: OngoingProcess, logsFile: File, outputFile: File?) {
        logger.debug("[{}] Running completion handler for {}: {}", nonce, process.taskID, process.status)

        if (process.status == ProcessStatus.COMPLETE_SUCCESS) {
            val logs = logsFile
                .takeIf(File::exists)
                ?.useLines { it.toList() }

            val output = outputFile
                ?.takeIf(File::exists)
                ?.readBytes()

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