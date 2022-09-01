package dev.brella.ytdlbox

import kotlinx.coroutines.Dispatchers

enum class CompletionActionType(val buildConfig: (box: YtdlBox) -> CompletionActionConfig) {
    RCLONE(::UploadWithRClone);

    operator fun invoke(box: YtdlBox): CompletionActionConfig? = buildConfig(box).takeIf(CompletionActionConfig::isAvailable)

    interface CompletionActionConfig {
        fun isAvailable(): Boolean
        fun buildFeatureSet(): YtdlBoxCompletionActionFeatureSet?
    }

    class UploadWithRClone(box: YtdlBox) : CompletionActionConfig {
        private val config =
            try {
                box.applicationConfig.config("rclone")
            } catch (th: Throwable) {
                null
            }

        val rcloneProcess: String? =
            config?.let {
                it.propertyOrNull("rclone")?.getString()
                ?: if (System.getProperty("os.name").contains("win", true)) "rclone.exe"
                else "rclone"
            }

        val endpoint: String? =
            config?.propertyOrNull("endpoint")?.getString()

        val basePath: String? =
            config?.propertyOrNull("base_path")?.getString()

        val resultingBaseUrl: String? =
            config?.propertyOrNull("resulting_base_url")?.getString()

        private val featureSet by lazy {
            val endpoint = endpoint ?: return@lazy null
            val endpointTypeProcess = ProcessBuilder(
                rcloneProcess ?: return@lazy null,
                "listremotes",
                "--long"
            ).start()

            val endpointTypeData = endpointTypeProcess
                .inputStream
                .readBytes()
                .decodeToString()

            endpointTypeProcess.waitFor()

//            val endpointType =
            YtdlBoxCompletionActionFeatureSet.RClone(
                rcloneProcess = rcloneProcess,
                endpoint = endpoint,
                endpointType = endpointTypeData
                    .split("\n")
                    .firstOrNull { line -> line.trim().startsWith(endpoint) }
                    ?.substringAfterLast(':')
                    ?.trim(),
                basePath = basePath,
                resultingBaseUrl = resultingBaseUrl
            )
        }

        override fun isAvailable(): Boolean = endpoint != null
        override fun buildFeatureSet(): YtdlBoxCompletionActionFeatureSet? = featureSet
    }
}