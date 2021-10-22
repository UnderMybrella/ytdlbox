package dev.brella.ytdlbox

import kotlinx.serialization.Serializable

enum class ProcessStatus(val isComplete: Boolean) {
    INITIALISING(false),
    RUNNING(false),
    COMPLETE_SUCCESS(true),
    COMPLETE_FAILURE(true),
    SHUTTING_DOWN(false);
}

@Serializable
data class DownloadRequest(val url: String, val args: List<String> = emptyList())

@Serializable
data class DownloadResponse(val id: String, val created: Boolean, val url: String, val args: List<String>)

@Serializable
sealed class TaskInfo {
    abstract val taskID: String
    abstract val url: String
    abstract val args: List<String>
    abstract val commandLine: List<String>
    abstract val logsLocation: String
    abstract val status: ProcessStatus
    abstract val logTail: String?

    @Serializable
    data class Generic(
        override val taskID: String,
        override val url: String,
        override val args: List<String>,
        override val commandLine: List<String>,
        override val logsLocation: String,
        override val status: ProcessStatus,
        override val logTail: String?
    ) : TaskInfo()

    @Serializable
    data class Running(
        override val taskID: String,
        override val url: String,
        override val args: List<String>,
        override val commandLine: List<String>,
        override val logsLocation: String,
        override val status: ProcessStatus,
        override val logTail: String?,
        val processInfo: ProcessHandleInfo?,
        val processError: String?
    ) : TaskInfo() {

        @Serializable
        data class ProcessHandleInfo(
            val pid: Long,
            val command: String?,
            val arguments: List<String>,
            val commandLine: String?,
            val startTime: String?
        )
    }

    @Serializable
    data class Successful(
        override val taskID: String,
        override val url: String,
        override val args: List<String>,
        override val commandLine: List<String>,
        override val logsLocation: String,
        override val status: ProcessStatus,
        override val logTail: String?,
        val dataLocation: String
    ) : TaskInfo()
}

@Serializable
data class DownloadProxy(val address: String, val limit: Int, val errorLimit: Int = 3)

@Serializable
data class WebError(val id: String, val errorMessage: String)

@Serializable
sealed class WebsocketRequest {
    abstract val nonce: Long

    @Serializable
    data class Download(override val nonce: Long, val request: DownloadRequest, val listenFor: Boolean = true) : WebsocketRequest()

    @Serializable
    data class AddProxyServer(override val nonce: Long, val proxy: DownloadProxy) : WebsocketRequest()

    @Serializable
    data class RemoveProxyServer(override val nonce: Long, val address: String) : WebsocketRequest()
}

@Serializable
sealed class WebsocketResponse {
    abstract val nonce: Long

    @Serializable
    data class Downloading(override val nonce: Long, val taskID: String, val created: Boolean, val url: String, val args: List<String>) : WebsocketResponse()

    @Serializable
    data class AddedProxyServer(override val nonce: Long, val proxy: DownloadProxy) : WebsocketResponse()

    @Serializable
    data class RemovedProxyServer(override val nonce: Long, val proxy: DownloadProxy?) : WebsocketResponse()

    @Serializable
    data class NoProxyListener(override val nonce: Long) : WebsocketResponse()

    @Serializable
    data class DownloadFailure(
        override val nonce: Long,
        val taskID: String,
        val url: String,
        val commandLine: List<String>,
        val logs: List<String>
    ) : WebsocketResponse()

    @Suppress("ArrayInDataClass")
    @Serializable
    data class DownloadSuccess(
        override val nonce: Long,
        val taskID: String,
        val url: String,
        val commandLine: List<String>,
        val output: @Serializable(Base64ByteArraySerialiser::class) ByteArray,
        val mimeType: String?,
        val logs: List<String>
    ) : WebsocketResponse()
}