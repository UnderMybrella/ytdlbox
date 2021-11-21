package dev.brella.ytdlbox

import kotlinx.serialization.Serializable

@Serializable
sealed class YtdlError {
    @Serializable
    object Unknown: YtdlError()
    @Serializable
    object VideoNotAvailable: YtdlError()
    @Serializable
    object ConnectionForciblyClosed: YtdlError()
}
