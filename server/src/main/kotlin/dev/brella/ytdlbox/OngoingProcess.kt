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
        inline fun beginDownloadFor(box: YtdlBox, url: String, options: List<String>, completionRequests: List<CompletionRequest>, key: String): OngoingProcess =
            box.OngoingProcess(box.generateTaskID(), url, options) { ongoing ->
                ongoing.status = ProcessStatus.INITIALISING

                val logFile = box.logFileForTask(ongoing.taskID)
                val holdingTemplate = box.outputTemplateForTask(ongoing.taskID)

                var videoError: YtdlError? = null

                logger.info("[${ongoing.taskID}] Download requested for {} ({})", url, options)
                val start = TimeSource.Monotonic.markNow()

                for (i in 0 until 5) {
                    if (videoError != null && videoError != YtdlError.Unknown) break
                    videoError = null

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

                        if (ongoing.process.exitValue() <= 0) break

                        videoError = YtdlError.Unknown
                        if (logFile.exists()) {
                            logFile.useLines { seq ->
                                seq.forEach { line ->
                                    when {
                                        line.contains("ERROR", true) -> {
                                            when {
                                                line.contains("This video is not available", true) -> {
                                                    logger.warn("[{}] {} was marked as unavailable", ongoing.taskID, url)

                                                    videoError = YtdlError.VideoNotAvailable
                                                    return@useLines
                                                }
                                                line.contains("forcibly closed by the remote host", true) -> {
                                                    logger.warn("[{}] forcibly closed connection to {}", ongoing.taskID, url)

                                                    videoError = YtdlError.ConnectionForciblyClosed
                                                    return@useLines
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!ongoing.onFailure.fold(true) { acc, func -> acc && func(ongoing, logFile, videoError) }) break
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    } finally {
                        if (proxy != null) {
                            var proxyError = false

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

                val wasSuccessful = videoError == null

                val end = start.elapsedNow()
                logger.info("[${ongoing.taskID}] Download complete for {} in {}", url, end)
                ongoing.status = if (wasSuccessful) ProcessStatus.COMPLETE_SUCCESS else ProcessStatus.COMPLETE_FAILURE
                val outputFile = box.outputFileForTask(ongoing.taskID)

                try {
                    if (wasSuccessful && outputFile?.exists() == true) ongoing.onSuccess.forEach { it(ongoing, logFile, outputFile) }
                    ongoing.onComplete.forEach { it(ongoing, logFile, outputFile, videoError) }

                    delay(120_000)

                    ongoing.status = ProcessStatus.SHUTTING_DOWN

                    delay(60_000)

                    box.ongoingTasks.remove(ongoing.taskID)
                    box.incomingUrls.remove(key)
                } finally {
                    logFile.delete()
                    outputFile?.delete()
                }

//            logFileForTask(ongoing.taskID).delete()
//            outputFileForTask(ongoing.taskID)?.delete()
            }.also { process ->
                box.ongoingTasks[process.taskID] = process
                box.incomingUrls[key] = process.taskID

                CompletionAction.parseRequests(completionRequests)
                    .forEach { action -> process.onComplete.add(action, box) }
            }

        inline fun MutableList<(suspend (process: OngoingProcess, logFile: File, outputFile: File?, error: YtdlError?) -> Unit)>.add(action: CompletionAction<*>, bind: YtdlBox) =
            add { process, logFile, outputFile, error -> action.onCompletion(bind, process, logFile, outputFile, error) }
    }

    /** Invoked on success */
    var onSuccess: MutableList<(suspend (process: OngoingProcess, logFile: File, outputFile: File) -> Unit)> = ArrayList()

    /** Invoked on failure. Return true to try again */
    var onFailure: MutableList<(suspend (process: OngoingProcess, logFile: File, error: YtdlError?) -> Boolean)> = ArrayList()

    /** Invoked on completion */
    var onComplete: MutableList<(suspend (process: OngoingProcess, logFile: File, outputFile: File?, error: YtdlError?) -> Unit)> = ArrayList()

    var commandLine: List<String> = emptyList()
    lateinit var process: Process
    var status: ProcessStatus = ProcessStatus.INITIALISING
}