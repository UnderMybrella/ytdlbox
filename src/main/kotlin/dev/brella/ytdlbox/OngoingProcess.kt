package dev.brella.ytdlbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

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

    /** Invoked on success */
    var onSuccess: (suspend (logFile: File, outputFile: File) -> Unit)? = null

    /** Invoked on failure. Return true to try again */
    var onFailure: (suspend (logFile: File) -> Boolean)? = null

    /** Invoked on completion */
    var onComplete: (suspend (logFile: File, outputFile: File?) -> Unit)? = null

    var commandLine: List<String> = emptyList()
    lateinit var process: Process
    var status: ProcessStatus = ProcessStatus.INITIALISING
}

enum class ProcessStatus {
    INITIALISING,
    RUNNING,
    COMPLETE_SUCCESS,
    COMPLETE_FAILURE;
}