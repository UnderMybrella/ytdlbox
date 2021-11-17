package dev.brella.ytdlbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YtdlBoxFeatureSet(
    val ytdlProcess: String,
    val defaultArgs: List<String>,
    val rotatingIpv6: Boolean,
    val listeningForProxy: Boolean,
    val completionActions: List<YtdlBoxCompletionActionFeatureSet>
)

@Serializable
sealed class YtdlBoxCompletionActionFeatureSet {
    @Serializable
    @SerialName("rclone")
    data class RClone(
        val rcloneProcess: String,
        val endpoint: String,
        val endpointType: String?,
        val basePath: String?,
        val resultingBaseUrl: String?
    ): YtdlBoxCompletionActionFeatureSet()
}