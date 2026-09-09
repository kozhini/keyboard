package dev.souchastnik.ai

import android.util.Log
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
        data object NoModel : State
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
            // AICore owns the shared Gemini Nano model. We deliberately do not
            // trigger a model download here: on supported Pixel devices the
            // system-provisioned model is expected to be available already.
            val status = model.checkStatus()
            ready = status == FeatureStatus.AVAILABLE
            emit(if (ready) State.Clean else State.NoModel)
            ready
        } catch (t: Throwable) {
            ready = false
            Log.w(TAG, "Gemini Nano status check failed", t)
            emit(State.NoModel)
            false
        }
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
        if (!ready) return@withContext State.NoModel

        val match = Triggers.match(text)
        if (match.codes.isEmpty()) return@withContext State.Clean

        val candidates = match.codes.distinct().filter { Articles[it] != null }
        if (candidates.isEmpty()) return@withContext State.Clean

        val table = candidates.joinToString("\n") { code ->
            Articles[code]!!.let { "${it.code} — ${it.act} — ${it.title}" }
        }
        val clean = match.clean.take(8).joinToString("\n")
        val prompt = buildString {
            appendLine("You are a strict text classifier.")
            appendLine("Choose exactly one code from the candidate list, or none.")
            appendLine("Return ONLY the code, with no explanation and no punctuation.")
            appendLine("A trigger is only a candidate signal; decide from the full text.")
            if (clean.isNotEmpty()) {
                appendLine("Counterexamples:")
                appendLine(clean)
            }
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
