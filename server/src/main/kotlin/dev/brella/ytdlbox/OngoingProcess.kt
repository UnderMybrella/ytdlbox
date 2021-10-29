package dev.brella.ytdlbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

class OngoingProcess(val job: Job, val taskID: String, val url: String, val parameters: List<String>) : Job by job {
    companion object {
        public inline operator fun invoke(scope: CoroutineScope, taskID: String, url: String, parameters: List<String>, crossinline block: suspend CoroutineScope.(OngoingProcess) -> Unit): OngoingProcess {
            val deferred = CompletableDeferred<OngoingProcess>()
            val ongoing = OngoingProcess(scope.launch(start = CoroutineStart.LAZY) { block(deferred.await()) }, taskID, url, parameters)

            deferred.complete(ongoing)
            ongoing.job.start()

            return ongoing
        }

        inline fun CoroutineScope.OngoingProcess(taskID: String, url: String, parameters: List<String>, crossinline block: suspend CoroutineScope.(OngoingProcess) -> Unit) =
            OngoingProcess.invoke(this, taskID, url, parameters, block)

        val logger = LoggerFactory.getLogger("OngoingProcess")

        @OptIn(ExperimentalTime::class)
        inline fun beginDownloadFor(box: YtdlBox, url: String, options: List<String>): OngoingProcess =
            box.OngoingProcess(box.generateTaskID(), url, options) { ongoing ->
                ongoing.status = ProcessStatus.INITIALISING

                val logFile = box.logFileForTask(ongoing.taskID)
                val holdingTemplate = box.outputTemplateForTask(ongoing.taskID)

                var successful: Boolean = false

                logger.info("[${ongoing.taskID}] Download requested for {} ({})", url, options)
                val start = TimeSource.Monotonic.markNow()

                for (i in 0 until 5) {
                    val proxy = box.proxyListener?.borrowProxy()

                    try {
                        val fullCommandLine = java.util.ArrayList<String>().apply {
                            add(box.ytdlProcess)
                            addAll(box.ytdlArgs)
                            addAll(options)

                            if (proxy != null) {
                                add("--proxy")
                                add(proxy.address)
                            } else if (box.sourceAddresses?.isClosedForReceive == false) {
                                val ip = box.sourceAddresses.receive()

                                logger.debug("[${ongoing.taskID}]  Using $ip")

                                if (ip.isIPv6) add("--force-ipv6")
                                else if (ip.isIPv6) add("--force-ipv4")

                                add("--source-address")
                                add(ip.toCanonicalString())
                            }

                            add("-o")
                            add(holdingTemplate)

                            add(ongoing.url)
                        }

                        ongoing.commandLine = fullCommandLine
                        ongoing.process = ProcessBuilder(fullCommandLine)
                            .redirectOutput(logFile)
                            .redirectErrorStream(true)
                            .start()

                        ongoing.status = ProcessStatus.RUNNING

                        try {
                            ongoing.process
                                .toHandle()
                                .onExit()
                                .await()
                        } catch (uoe: UnsupportedOperationException) {
                            ongoing.process.waitFor()
                        }

                        successful = ongoing.process.exitValue() <= 0
                        if (successful) break
                        if (!ongoing.onFailure.fold(true) { acc, func -> acc && func(ongoing, logFile) }) break
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    } finally {
                        if (proxy != null) {
                            var proxyError: Boolean = false

                            if (logFile.exists()) {
                                logFile.useLines { seq ->
                                    seq.forEach { line ->
                                        if (line.contains("ERROR", true) && line.contains("forcibly closed by the remote host", true)) {
                                            logger.warn("[${ongoing.taskID}] Warning $proxy due to $line")

                                            proxyError = true
                                            return@useLines
                                        }
                                    }
                                }
                            }

                            box.proxyListener.returnProxy(proxy, proxyError)
                        }
                    }
                }

                val end = start.elapsedNow()
                logger.info("[${ongoing.taskID}] Download complete for {} in {}", url, end)
                ongoing.status = if (successful) ProcessStatus.COMPLETE_SUCCESS else ProcessStatus.COMPLETE_FAILURE
                val outputFile = box.outputFileForTask(ongoing.taskID)

                try {
                    if (successful && outputFile?.exists() == true) ongoing.onSuccess.forEach { it(ongoing, logFile, outputFile) }
                    ongoing.onComplete.forEach { it(ongoing, logFile, outputFile) }

                    delay(120_000)

                    ongoing.status = ProcessStatus.SHUTTING_DOWN

                    delay(60_000)

                    box.ongoingTasks.remove(ongoing.taskID)
                    box.incomingUrls.remove(ongoing.url)
                } finally {
                    logFile.delete()
                    outputFile?.delete()
                }

//            logFileForTask(ongoing.taskID).delete()
//            outputFileForTask(ongoing.taskID)?.delete()
            }.also { process ->
                box.ongoingTasks[process.taskID] = process
                box.incomingUrls[process.url] = process.taskID
            }
    }

    /** Invoked on success */
    var onSuccess: MutableList<(suspend (process: OngoingProcess, logFile: File, outputFile: File) -> Unit)> = ArrayList()

    /** Invoked on failure. Return true to try again */
    var onFailure: MutableList<(suspend (process: OngoingProcess, logFile: File) -> Boolean)> = ArrayList()

    /** Invoked on completion */
    var onComplete: MutableList<(suspend (process: OngoingProcess, logFile: File, outputFile: File?) -> Unit)> = ArrayList()

    var commandLine: List<String> = emptyList()
    lateinit var process: Process
    var status: ProcessStatus = ProcessStatus.INITIALISING
}