package dev.brella.ytdlbox

import java.io.File

sealed class CompletionAction<T> {
    data class UploadWithRClone(val path: String) : CompletionAction<ProcessOutput?>() {
        constructor(request: CompletionRequest.UploadWithRClone) : this(request.path)

        override suspend fun onCompletion(box: YtdlBox, process: OngoingProcess, logFile: File, outputFile: File?, error: YtdlError?): ProcessOutput? {
            val config = box.actionConfigs[CompletionActionType.RCLONE] as? CompletionActionType.UploadWithRClone ?: return null

            val rcloneProcess = config.rcloneProcess ?: return null
            val rcloneEndpoint = config.endpoint ?: return null

            if (outputFile?.exists() == true) {
                return ProcessBuilder(
                    listOf(
                        rcloneProcess,
                        "copyto",
                        outputFile.absolutePath,
                        buildString {
                            append(rcloneEndpoint)
                            append(':')
                            if (config.basePath != null) {
                                append(config.basePath)
                                append('/')
                            }

                            append(path)
                        }
                    )
                ).startAndTap()
            }

            return null
        }
    }

    abstract suspend fun onCompletion(box: YtdlBox, process: OngoingProcess, logFile: File, outputFile: File?, error: YtdlError?): T

    companion object {
        inline fun parseRequests(requests: List<CompletionRequest>): List<CompletionAction<*>> =
            requests.mapNotNull { request ->
                when (request) {
                    is CompletionRequest.UploadWithRClone -> UploadWithRClone(request)

                    else -> null
                }
            }
    }
}