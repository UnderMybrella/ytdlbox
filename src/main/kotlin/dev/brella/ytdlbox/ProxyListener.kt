package dev.brella.ytdlbox

import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

sealed class ProxyAction<T>(val deferred: CompletableDeferred<T> = CompletableDeferred()) : CompletableDeferred<T> by deferred {
    class AddProxy(val proxy: DLProxy) : ProxyAction<Unit>()
    class RemoveProxy(val address: String) : ProxyAction<DLProxy?>()

    class BorrowProxy : ProxyAction<DLProxy?>()
    class ReturnProxy(val proxy: DLProxy, val hadError: Boolean) : ProxyAction<Unit>()
}

@Serializable
data class DLProxy(val address: String, val limit: Int, val errorLimit: Int = 3)

class ProxyListener(val scope: CoroutineScope, val saveFile: File = File("proxies.json")) {
    private val proxies: MutableMap<DLProxy, Int> = HashMap()
    private val errors: MutableMap<DLProxy, Int> = HashMap()

    private val channel = Channel<ProxyAction<*>>()
    private val listener = channel.consumeAsFlow()
        .onEach { action ->
            when (action) {
                is ProxyAction.AddProxy -> {
                    if (proxies.keys.none { it.address == action.proxy.address }) proxies.put(action.proxy, 0)

                    action.complete(Unit)
                }
                is ProxyAction.RemoveProxy -> {
                    val proxy = proxies.keys.firstOrNull { it.address == action.address }
                    if (proxy != null) proxies.remove(proxy)

                    action.complete(proxy)
                }
                is ProxyAction.BorrowProxy -> {
                    val entry = proxies.entries
                        .filter { (k, v) -> v < k.limit }
                        .minByOrNull(Map.Entry<DLProxy, Int>::value)

                    if (entry != null) proxies[entry.key] = entry.value
                    action.complete(entry?.key)
                }
                is ProxyAction.ReturnProxy -> {
                    val existingErrors = errors[action.proxy]
                    if (action.hadError) {
                        val newErrors = (existingErrors?.plus(1) ?: 1)
                        if (newErrors >= action.proxy.errorLimit) {
                            println("Retiring ${action.proxy} due to hitting $newErrors/${action.proxy.errorLimit} errors")
                            proxies.remove(action.proxy)
                            errors.remove(action.proxy)
                        } else {
                            proxies.computeIfPresent(action.proxy) { _, i -> i - 1 }
                            errors[action.proxy] = newErrors
                        }
                    } else {
                        proxies.computeIfPresent(action.proxy) { _, i -> i - 1 }
                    }

                    action.complete(Unit)
                }
            }
        }.launchIn(scope)
    private val saveJob = scope.launch { while (isActive) saveFile.writeText(Json.encodeToString(proxies.keys.toList())) }

    suspend fun addProxy(proxy: DLProxy) =
        ProxyAction.AddProxy(proxy)
            .also { channel.send(it) }
            .await()

    suspend fun removeProxy(address: String) =
        ProxyAction.RemoveProxy(address)
            .also { channel.send(it) }
            .await()

    suspend fun borrowProxy() =
        ProxyAction.BorrowProxy()
            .also { channel.send(it) }
            .await()

    suspend fun returnProxy(proxy: DLProxy, hadError: Boolean) =
        ProxyAction.ReturnProxy(proxy, hadError)
            .also { channel.send(it) }
            .await()

    init {
        if (saveFile.exists())
            Json.decodeFromString<List<DLProxy>>(saveFile.readText()).forEach { proxies[it] = 0 }
    }
}