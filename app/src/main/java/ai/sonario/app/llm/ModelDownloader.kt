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
 * Downloads a GGUF model into Sonario's private model directory with progress and
 * HTTP Range resume. Partial downloads remain as .part files until resumed or
 * Android removes the app's private data during uninstall.
 */
class ModelDownloader(private val modelsDir: File) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    sealed interface State {
        data class Progress(val bytes: Long, val total: Long) : State {
            val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
        }
        data class Done(val file: File) : State
        data class Failed(val message: String) : State
    }

    fun download(model: ModelInfo): Flow<State> = flow {
        modelsDir.mkdirs()
        val target = File(modelsDir, model.fileName)
        if (target.exists() && target.length() > 0L) {
            emit(State.Done(target))
            return@flow
        }

        val part = File(modelsDir, model.fileName + ".part")
        var offset = if (part.exists()) part.length() else 0L
        val estimatedBytes = model.sizeMb.toLong() * 1_000_000L
        val remainingEstimate = (estimatedBytes - offset).coerceAtLeast(0L)
        val requiredWithHeadroom = remainingEstimate + STORAGE_HEADROOM_BYTES

        if (modelsDir.usableSpace < requiredWithHeadroom) {
            val neededMb = requiredWithHeadroom / 1_000_000L
            val freeMb = modelsDir.usableSpace / 1_000_000L
            emit(
                State.Failed(
                    "Not enough free storage for ${model.label}. " +
                        "About $neededMb MB is needed, but only $freeMb MB is available."
                )
            )
            return@flow
        }

        val requestBuilder = Request.Builder()
            .url(model.downloadUrl)
            .header("User-Agent", "Sonario/1.5")
        if (offset > 0L) requestBuilder.header("Range", "bytes=$offset-")

        try {
            http.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    emit(
                        State.Failed(
                            "Model server returned ${response.code}. Check the connection and try again."
                        )
                    )
                    return@flow
                }

                val body = response.body ?: run {
                    emit(State.Failed("The model server returned an empty response."))
                    return@flow
                }

                // Some hosts ignore Range and return 200 with the complete file.
                // Restart rather than appending a second full copy to the partial.
                if (offset > 0L && response.code == 200) {
                    offset = 0L
                }

                val remaining = body.contentLength()
                val total = when {
                    response.code == 206 && remaining > 0L -> offset + remaining
                    remaining > 0L -> remaining
                    else -> estimatedBytes
                }

                RandomAccessFile(part, "rw").use { output ->
                    if (offset == 0L) output.setLength(0L)
                    output.seek(offset)
                    var written = offset
                    var lastEmit = offset

                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count

                            if (written - lastEmit >= PROGRESS_STEP_BYTES) {
                                emit(State.Progress(written, total))
                                lastEmit = written
                            }
                        }
                    }
                }

                val minimumPlausibleSize = estimatedBytes * 85L / 100L
                if (part.length() < minimumPlausibleSize) {
                    emit(
                        State.Failed(
                            "The model download ended early. Keep the partial file and tap Retry to resume."
                        )
                    )
                    return@flow
                }

                if (part.renameTo(target)) {
                    emit(State.Progress(target.length(), target.length()))
                    emit(State.Done(target))
                } else {
                    emit(State.Failed("Sonario could not finalize the downloaded model file."))
                }
            }
        } catch (error: Exception) {
            emit(State.Failed(error.message ?: "Model download failed."))
        }
    }.flowOn(Dispatchers.IO)

    fun deletePartial(model: ModelInfo) {
        File(modelsDir, model.fileName + ".part").delete()
    }

    companion object {
        private const val STORAGE_HEADROOM_BYTES = 256L * 1_000_000L
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
    }
}
