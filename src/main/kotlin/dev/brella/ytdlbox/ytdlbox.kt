package dev.brella.ytdlbox

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.future.await
import java.io.File
import java.util.*
import kotlin.collections.HashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

class OngoingProcess(val job: Job, val taskID: String, val url: String, val parameters: List<String>) : Job by job {
    companion object {
        public inline operator fun invoke(scope: CoroutineScope, taskID: String, url: String, parameters: List<String>, crossinline block: suspend CoroutineScope.(OngoingProcess) -> Unit): OngoingProcess {
            val deferred = CompletableDeferred<OngoingProcess>()
            val ongoing = OngoingProcess(scope.launch(start = CoroutineStart.LAZY) { block(deferred.await()) }, taskID, url, parameters)

            deferred.complete(ongoing)
            ongoing.job.start()

            return ongoing
        }
    }

    lateinit var process: Process
}

fun interface TradeOffer<I, O> {
    public suspend fun complete(input: I): O
}

class TradeOfferImpl<I, O>(val incoming: CompletableDeferred<I>, val outgoing: Deferred<O>) : TradeOffer<I, O> {
    override suspend fun complete(input: I): O {
        incoming.complete(input)
        return outgoing.await()
    }
}

inline fun <T> CoroutineScope.launchLazyWithParam(
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend CoroutineScope.(T) -> Unit
): TradeOffer<T, Job> {
    val incoming = CompletableDeferred<T>(context[Job])
    val job = launch(context = context, start = CoroutineStart.LAZY) { block(incoming.await()) }

    return TradeOffer {
        incoming.complete(it)
        job.start()
        return@TradeOffer job
    }
}

inline fun CoroutineScope.OngoingProcess(taskID: String, url: String, parameters: List<String>, crossinline block: suspend CoroutineScope.(OngoingProcess) -> Unit) =
    OngoingProcess.invoke(this, taskID, url, parameters, block)

class YtdlBox(val application: Application) : CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    private val ongoingTasks: MutableMap<String, OngoingProcess> = HashMap()
    private val incomingUrls: MutableMap<String, String> = HashMap()

    private inline fun generateTaskID(): String = UUID.randomUUID().toString()

    private val applicationConfig = application
        .environment
        .config
        .config("ytdlbox")

    private val ytdlProcess = applicationConfig
                                  .propertyOrNull("youtube-dl")
                                  ?.getString() ?: if (System.getProperty("os.name").contains("win", true)) "youtube-dl.exe" else "youtube-dl"

    private val ytdlArgs = applicationConfig
                               .propertyOrNull("args")
                               ?.getList() ?: emptyList()

    private val rotateAmongIPv6 = applicationConfig
        .propertyOrNull("rotate_among_ipv6")
        ?.getString()

    private val sourceAddresses = rotateAmongIPv6
        ?.let(::IPAddressString)
        ?.address?.let { base ->
            produce<IPAddress> {
                var seqBlocks: Iterator<IPAddress> = object: Iterator<IPAddress> {
                    override fun next(): IPAddress = throw IllegalStateException("Cannot call next on empty")
                    override fun hasNext(): Boolean = false
                }

                while (isActive) {
                    if (!seqBlocks.hasNext()) seqBlocks = base.sequentialBlockIterator()

                    seqBlocks.next()
                        .toSequentialRange()
                        .iterator()
                        .forEach { addr -> send(addr) }
                }
            }
        }

    private fun beginDownload(url: String, options: List<String>): OngoingProcess =
        OngoingProcess(generateTaskID(), url, options) { ongoing ->
            ongoing.process = ProcessBuilder(ArrayList<String>().apply {
                add(ytdlProcess)
                addAll(ytdlArgs)
                addAll(options)

                if (sourceAddresses?.isClosedForReceive == false) {
                    val ip = sourceAddresses.receive()

                    if (ip.isIPv6) add("--force-ipv6")
                    else if (ip.isIPv6) add("--force-ipv4")

                    add("--source-address")
                    add(ip.toCanonicalString())
                }

                add(ongoing.url)

                println("Running ${this.joinToString(" ")}")
            }).redirectOutput(File("${ongoing.taskID}.log"))
                .redirectErrorStream(true)
                .start()

            try {
                ongoing.process
                    .toHandle()
                    .onExit()
                    .await()
            } catch (uoe: UnsupportedOperationException) {
                ongoing.process.waitFor()
            }

        }.also { process ->
            ongoingTasks[process.taskID] = process
            incomingUrls[process.url] = process.taskID
        }

    fun setupRouting(routing: Routing) =
        with(routing) {
            get("/") {
                call.respondText("Hello, world!")
            }

            get("/test") {
                val process = beginDownload("https://www.youtube.com/watch?v=v9QD25nakt8", emptyList())

                call.respondText(process.taskID)
            }

            get("/{task_id}") {
                call.respondText(ongoingTasks[call.parameters.getOrFail("task_id")].toString())
            }
        }

    init {
        application.routing(this::setupRouting)
    }
}

fun Application.module() = YtdlBox(this)