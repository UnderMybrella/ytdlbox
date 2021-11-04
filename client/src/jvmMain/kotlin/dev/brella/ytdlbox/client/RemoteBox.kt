package dev.brella.ytdlbox.client

import dev.brella.ytdlbox.CompletionRequest
import dev.brella.ytdlbox.DownloadProxy
import dev.brella.ytdlbox.DownloadRequest
import dev.brella.ytdlbox.ListenCondition
import dev.brella.ytdlbox.WebsocketRequest
import dev.brella.ytdlbox.WebsocketResponse
import io.ktor.client.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlin.random.Random

class RemoteBox(val connection: WebSocketSession, val format: SerialFormat) : WebSocketSession by connection {
    val incomingFlow = when (format) {
        is StringFormat -> incoming.receiveAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { frame -> format.decodeFromString<WebsocketResponse>(frame.readText()) }
//            .onEach { println("Receiving ${it::class.simpleName}") }
            .shareIn(this, SharingStarted.Eagerly, 0)

        is BinaryFormat -> incoming.receiveAsFlow()
            .filterIsInstance<Frame.Binary>()
            .map { frame -> format.decodeFromByteArray<WebsocketResponse>(frame.readBytes()) }
//            .onEach { println("Receiving ${it::class.simpleName}") }
            .shareIn(this, SharingStarted.Eagerly, 0)

        else -> error("Unknown SerialFormat $format (${format::class})")
    }

    val outgoingChannel = Channel<WebsocketRequest>(16)
    val outgoingJob = when (format) {
        is StringFormat -> outgoingChannel.receiveAsFlow()
            .onEach { response -> send(format.encodeToString(response)); flush() }
            .launchIn(this)

        is BinaryFormat -> outgoingChannel.receiveAsFlow()
            .onEach { response -> send(format.encodeToByteArray(response)); flush() }
            .launchIn(this)

        else -> error("Unknown SerialFormat $format (${format::class})")
    }

    private val base = (Random.nextLong() * 31 + connection.hashCode()) shl 32
    private var incr: Long = 0

    private inline fun nonce(): Long =
        base or incr++

    suspend inline fun send(request: WebsocketRequest) =
        outgoingChannel.send(request)

    suspend inline fun <reified T : WebsocketResponse> sendAndWait(request: WebsocketRequest): T {
        send(request)
        return incomingFlow.first { response -> response.nonce == request.nonce && response is T } as T
    }

    suspend inline fun <reified T : WebsocketResponse, reified R : WebsocketResponse>
            sendAndWaitWithError(request: WebsocketRequest): Pair<T?, R?> {
        send(request)
        val response = incomingFlow.first { response -> response.nonce == request.nonce && (response is T || response is R) }

        return (response as? T) to (response as? R)
    }

    suspend fun downloadWithData(url: String, args: List<String>, completionRequests: List<CompletionRequest>): Pair<WebsocketResponse.DownloadSuccess?, WebsocketResponse.DownloadFailure?> =
        sendAndWaitWithError(WebsocketRequest.Download(nonce(), DownloadRequest(url, args, completionRequests), ListenCondition.LISTEN_WITH_DATA))

    suspend fun downloadWithoutData(url: String, args: List<String>, completionRequests: List<CompletionRequest>): Pair<WebsocketResponse.DownloadSuccess?, WebsocketResponse.DownloadFailure?> =
        sendAndWaitWithError(WebsocketRequest.Download(nonce(), DownloadRequest(url, args, completionRequests), ListenCondition.LISTEN_NO_DATA))

    suspend fun beginDownload(url: String, args: List<String>, completionRequests: List<CompletionRequest>): WebsocketResponse.Downloading =
        sendAndWait(WebsocketRequest.Download(nonce(), DownloadRequest(url, args, completionRequests), ListenCondition.DO_NOT_LISTEN))

    suspend fun addProxyServer(address: String, limit: Int, errorLimit: Int = 3): Pair<WebsocketResponse.AddedProxyServer?, WebsocketResponse.NoProxyListener?> =
        sendAndWaitWithError(WebsocketRequest.AddProxyServer(nonce(), DownloadProxy(address, limit, errorLimit)))

    suspend fun removeProxyServer(address: String): Pair<WebsocketResponse.RemovedProxyServer?, WebsocketResponse.NoProxyListener?> =
        sendAndWaitWithError(WebsocketRequest.RemoveProxyServer(nonce(), address))
}

suspend fun HttpClient.remoteBox(urlString: String, auth: String, format: Pair<ContentType, SerialFormat>, builder: HttpRequestBuilder.() -> Unit = {}): RemoteBox =
    RemoteBox(webSocketSession {
        url(urlString)
        builder()

        url {
            protocol = if (protocol.isSecure()) URLProtocol.WSS else URLProtocol.WS
        }

        header("Accept", format.first.toString())
        header("Authorization", auth)
    }, format.second)