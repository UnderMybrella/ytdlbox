package dev.brella.ytdlbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext

suspend inline fun buildProcessAndTap(vararg command: String, context: CoroutineContext = Dispatchers.IO, builder: ProcessBuilder.() -> Unit): ProcessOutput =
    ProcessBuilder(command.asList())
        .apply(builder)
        .startAndTap(context)

@Suppress("BlockingMethodInNonBlockingContext")
suspend inline fun ProcessBuilder.startAndTap(context: CoroutineContext = Dispatchers.IO): ProcessOutput =
    withContext(context) { start().tap(this, context) }

@Suppress("BlockingMethodInNonBlockingContext")
suspend inline fun Process.tap(scope: CoroutineScope, context: CoroutineContext = Dispatchers.IO): ProcessOutput {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    val stdoutJob = scope.launch(context) {
        val stream = inputStream

        var bytesCopied: Long = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = stream.read(buffer)
        while (bytes >= 0) {
            stdout.write(buffer, 0, bytes)
            bytesCopied += bytes
            yield()
            bytes = stream.read(buffer)
        }
    }
    val stderrJob = scope.launch(context) {
        val stream = errorStream

        var bytesCopied: Long = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = stream.read(buffer)
        while (bytes >= 0) {
            stderr.write(buffer, 0, bytes)
            bytesCopied += bytes
            yield()
            bytes = stream.read(buffer)
        }
    }

    try {
        toHandle()
            .onExit()
            .await()
    } catch (uoe: UnsupportedOperationException) {
        waitFor()
    }

    stdoutJob.cancelAndJoin()
    stderrJob.cancelAndJoin()

//    println(stdout.toByteArray().decodeToString())
//    println(stderr.toByteArray().decodeToString())

    return ProcessOutput(exitValue(), stdout.toByteArray(), stderr.toByteArray())
}