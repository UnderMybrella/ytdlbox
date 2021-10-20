package dev.brella.ytdlbox

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import io.ktor.routing.get as getSuper


/**
 * Responds to a client with a plain text response, using specified [provider] to build a text
 * @param contentType is an optional [ContentType], default is [ContentType.Text.Plain]
 * @param status is an optional [HttpStatusCode], default is [HttpStatusCode.OK]
 */
public suspend fun ApplicationCall.respondJsonObject(
    contentType: ContentType? = ContentType.Application.Json,
    status: HttpStatusCode? = null,
    provider: suspend JsonObjectBuilder.() -> Unit
) {
    val message = TextContent(buildJsonObject { provider() }.toString(), defaultTextContentType(contentType), status)
    respond(message)
}

@ContextDsl
public inline fun Route.get(path: String, noinline body: PipelineInterceptor<Unit, ApplicationCall>): Route =
    getSuper(path, body)
@ContextDsl
public inline fun Route.get(vararg paths: String, noinline body: PipelineInterceptor<Unit, ApplicationCall>): List<Route> =
    paths.map { getSuper(it, body) }