package dev.brella.ytdlbox

import io.ktor.config.*

enum class CompletionActionType(val buildConfig: (box: YtdlBox) -> CompletionActionConfig) {
    RCLONE(::UploadWithRClone);

    operator fun invoke(box: YtdlBox): CompletionActionConfig? = buildConfig(box).takeIf(CompletionActionConfig::isAvailable)

    interface CompletionActionConfig {
        fun isAvailable(): Boolean
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

        override fun isAvailable(): Boolean = endpoint != null
    }
}