package dev.souchastnik.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dev.souchastnik.data.Article
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Triggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On-device Gemini Nano classifier used directly by the IME process. */
class GeminiNanoClient {
    companion object {
        private const val TAG = "souchastnik-gemini"
        private const val MIN_CHARS = 12
        private const val MAX_INPUT_CHARS = 12000
    }

    sealed interface State {
        data class NoModel(val detail: String = "Gemini Nano недоступен") : State
        data object Loading : State
        data object Clean : State
        data object Thinking : State
        data class Verdict(val article: Article) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var analyzeJob: Job? = null
    private var prepareJob: Job? = null
    private val model by lazy { Generation.getClient() }
    private var ready = false

    var onState: ((State) -> Unit)? = null

    private suspend fun emit(state: State) = withContext(Dispatchers.Main.immediate) {
        onState?.invoke(state)
    }

    suspend fun prepare(): Boolean = withContext(Dispatchers.Default) {
        if (ready) return@withContext true

        emit(State.Loading)
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    markReady()
                    true
                }

                FeatureStatus.DOWNLOADABLE,
                FeatureStatus.DOWNLOADING -> {
                    Log.i(TAG, "Gemini Nano needs download: ${statusName(model.checkStatus())}")
                    emit(State.NoModel("Gemini Nano: DOWNLOADING"))

                    model.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadStarted -> {
                                Log.i(TAG, "Gemini Nano download started: ${status.bytesToDownload} bytes")
                                emit(State.NoModel("Gemini Nano: DOWNLOADING"))
                            }

                            is DownloadStatus.DownloadProgress -> {
                                Log.i(TAG, "Gemini Nano download progress: ${status.totalBytesDownloaded} bytes")
                            }

                            DownloadStatus.DownloadCompleted -> {
                                Log.i(TAG, "Gemini Nano download completed")
                            }

                            is DownloadStatus.DownloadFailed -> {
                                val message = status.e.message?.takeIf { it.isNotBlank() }
                                    ?: status.e.javaClass.simpleName
                                Log.e(TAG, "Gemini Nano download failed: $message", status.e)
                                emit(State.NoModel("Gemini Nano: DOWNLOAD_FAILED: $message"))
                            }
                        }
                    }

                    if (model.checkStatus() == FeatureStatus.AVAILABLE) {
                        markReady()
                        true
                    } else {
                        val status = model.checkStatus()
                        val detail = "Gemini Nano: ${statusName(status)}"
                        Log.w(TAG, detail)
                        emit(State.NoModel(detail))
                        false
                    }
                }

                else -> {
                    ready = false
                    val status = model.checkStatus()
                    val detail = "Gemini Nano: ${statusName(status)}"
                    Log.w(TAG, detail)
                    emit(State.NoModel(detail))
                    false
                }
            }
        } catch (t: Throwable) {
            ready = false
            val detail = buildString {
                append("Gemini Nano: ошибка ")
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }
            Log.w(TAG, "Gemini Nano status/download failed", t)
            emit(State.NoModel(detail))
            false
        }
    }

    private suspend fun markReady() {
        val modelName = runCatching { model.getBaseModelName() }
            .getOrElse { "unknown (${it.javaClass.simpleName})" }
        ready = true
        Log.i(TAG, "Gemini Nano available: $modelName")
        emit(State.Clean)
    }

    private fun statusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "status=$status"
    }

    fun prepareInBackground(onReady: (() -> Unit)? = null) {
        if (prepareJob?.isActive == true || ready) {
            if (ready) onReady?.invoke()
            return
        }
        prepareJob = scope.launch {
            if (prepare()) onReady?.invoke()
        }
    }

    suspend fun analyze(text: String): State = withContext(Dispatchers.Default) {
        if (text.trim().length < MIN_CHARS) return@withContext State.Clean
        if (!ready) return@withContext State.NoModel()

        // The trigger dictionary is only a cheap gate and candidate reducer.
        // No trigger means no model call; a trigger never selects the article.
        val match = Triggers.match(text)
        if (match.codes.isEmpty()) return@withContext State.Clean

        val candidates = match.codes.distinct().filter { Articles[it] != null }
        if (candidates.isEmpty()) return@withContext State.Clean

        val table = candidates.joinToString("\n") { code ->
            Articles[code]!!.let { "${it.code} — ${it.act} — ${it.title}" }
        }
        val prompt = buildString {
            appendLine("You are a strict text classifier.")
            appendLine("A trigger only means that this text must be evaluated.")
            appendLine("A trigger alone is not evidence of a violation.")
            appendLine("Judge the meaning of the complete text.")
            appendLine("Choose exactly one code from the candidate list, or none.")
            appendLine("Return ONLY the code, with no explanation and no punctuation.")
            appendLine("Counterexamples:")
            match.clean.distinct().take(8).forEach(::appendLine)
            appendLine("Candidates:")
            appendLine(table)
            appendLine("Allowed fallback: none")
            appendLine("Text:")
            append(text.take(MAX_INPUT_CHARS))
        }

        emit(State.Thinking)
        try {
            val response = model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.0f
                    seed = 1
                    topK = 1
                    candidateCount = 1
                    maxOutputTokens = 8
                    enableThinking = false
                },
            )
            val output = response.candidates.firstOrNull()?.text?.trim().orEmpty()
            val code = parseCode(output, candidates)
            if (code == Articles.NONE) State.Clean
            else Articles[code]?.let(State::Verdict) ?: State.Clean
        } catch (t: Throwable) {
            Log.w(TAG, "Gemini Nano inference failed", t)
            State.Clean
        }
    }

    private fun parseCode(output: String, candidates: List<String>): String {
        if (output.equals("none", ignoreCase = true)) return Articles.NONE
        return candidates.firstOrNull { code ->
            Regex("(?<![\\d.])${Regex.escape(code)}(?![\\d.])").containsMatchIn(output)
        } ?: Articles.NONE
    }

    fun launchAnalyze(text: String) {
        analyzeJob?.cancel()
        analyzeJob = scope.launch {
            val state = analyze(text)
            withContext(Dispatchers.Main.immediate) { onState?.invoke(state) }
        }
    }

    fun cancel() {
        analyzeJob?.cancel()
        analyzeJob = null
    }

    fun close() {
        cancel()
        prepareJob?.cancel()
        model.close()
        scope.cancel()
    }
}
