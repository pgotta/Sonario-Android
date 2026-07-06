package ai.sonario.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads a GGUF model into the app's models dir, with progress and resume.
 * This is what makes step 3 ("get a model onto the phone") happen in-app instead
 * of by adb. Resume uses an HTTP Range request against a .part file, so a dropped
 * connection on a 1-2GB download doesn't start over.
 */
class ModelDownloader(private val modelsDir: File) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed interface State {
        data class Progress(val bytes: Long, val total: Long) : State {
            val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
        }
        data class Done(val file: File) : State
        data class Failed(val message: String) : State
    }

    /**
     * Stream download states. The final emission is Done or Failed. Cancelling
     * the collecting coroutine stops the download and leaves the .part file in
     * place for a later resume.
     */
    fun download(model: ModelInfo): Flow<State> = flow {
        val target = File(modelsDir, model.fileName)
        if (target.exists() && target.length() > 0) {
            emit(State.Done(target)); return@flow
        }
        val part = File(modelsDir, model.fileName + ".part")
        val have = if (part.exists()) part.length() else 0L

        val reqBuilder = Request.Builder().url(model.downloadUrl)
            .header("User-Agent", "Sonario/1.0")
        if (have > 0) reqBuilder.header("Range", "bytes=$have-")

        try {
            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    emit(State.Failed("Server returned ${resp.code}. " +
                        "Check the connection and try again."))
                    return@flow
                }
                val body = resp.body ?: run {
                    emit(State.Failed("Empty response from server.")); return@flow
                }

                // total = already-downloaded + remaining content length
                val remaining = body.contentLength()
                val total = if (remaining > 0) have + remaining
                            else model.sizeMb.toLong() * 1_000_000L  // estimate

                val raf = RandomAccessFile(part, "rw")
                raf.seek(have)
                var written = have

                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (true) {
                        coroutineContext.ensureActive()  // honour cancellation
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n
                        // throttle UI updates to ~every 512KB
                        if (written - lastEmit > 512 * 1024) {
                            emit(State.Progress(written, total))
                            lastEmit = written
                        }
                    }
                }
                raf.close()

                if (part.renameTo(target)) {
                    emit(State.Progress(target.length(), target.length()))
                    emit(State.Done(target))
                } else {
                    emit(State.Failed("Could not finalize the downloaded file."))
                }
            }
        } catch (e: Exception) {
            emit(State.Failed(e.message ?: "Download failed."))
        }
    }.flowOn(Dispatchers.IO)

    fun deletePartial(model: ModelInfo) {
        File(modelsDir, model.fileName + ".part").delete()
    }
}
