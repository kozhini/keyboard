package dev.souchastnik.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Triggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gemini Nano adapter for the IME process.
 *
 * Important: ML Kit GenAI Prompt API is currently beta. The adapter therefore
 * treats every model response as untrusted text and accepts only an exact
 * article code from the trigger-produced candidate set, or "none".
 *
 * Qwen/llama.cpp is deliberately not removed by this change. Callers can
 * fall back to the existing EngineClient when Gemini Nano is unavailable or
 * returns an invalid result.
 */
class GeminiNanoClient(private val context: Context) {
    companion object {
        private const val TAG = "souchastnik-gemini"
        private const val MIN_CHARS = 12
        private const val MAX_INPUT_CHARS = 12000
    }

    sealed interface Result {
        data class Verdict(val code: String) : Result
        data class Unavailable(val reason: String) : Result
        data class Error(val error: Throwable) : Result
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val model by lazy { Generation.getClient() }

    suspend fun availability(): Result = withContext(Dispatchers.Default) {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Result.Verdict(Articles.NONE)
                FeatureStatus.DOWNLOADING -> Result.Unavailable("downloading")
                FeatureStatus.DOWNLOADABLE -> Result.Unavailable("downloadable")
                FeatureStatus.UNAVAILABLE -> Result.Unavailable("unavailable")
                else -> Result.Unavailable("unknown_status")
            }
        } catch (t: Throwable) {
            Result.Error(t)
        }
    }

    /**
     * Analyze one text snapshot. Previous inference is cancelled at the
     * coroutine level; stale responses are additionally rejected by the
     * caller's request id.
     */
    suspend fun analyze(text: String): Result = withContext(Dispatchers.Default) {
        if (text.trim().length < MIN_CHARS) return@withContext Result.Verdict(Articles.NONE)

        val status = availability()
        if (status !is Result.Verdict) return@withContext status

        val match = Triggers.match(text)
        if (match.codes.isEmpty()) return@withContext Result.Verdict(Articles.NONE)

        val candidates = match.codes.distinct().filter { Articles[it] != null }
        if (candidates.isEmpty()) return@withContext Result.Verdict(Articles.NONE)

        val table = candidates.joinToString("\n") { code ->
            Articles[code]!!.let { "${it.code} — ${it.act} — ${it.title}" }
        }
        val clean = match.clean.take(8).joinToString("\n")

        // Keep the prompt short and explicit. We do not ask Gemini Nano to
        // reproduce legal text or invent a legal explanation: it only selects
        // one code from the finite candidate set.
        val prompt = buildString {
            appendLine("You are a strict text classifier.")
            appendLine("Choose exactly one code from the candidate list, or none.")
            appendLine("Return ONLY the code, with no explanation and no punctuation.")
            appendLine("A trigger is only a candidate signal; decide from the full text.")
            if (clean.isNotEmpty()) {
                appendLine("Counterexamples from the trigger dictionary:")
                appendLine(clean)
            }
            appendLine("Candidates:")
            appendLine(table)
            appendLine("Allowed fallback: none")
            appendLine("Text:")
            append(text.take(MAX_INPUT_CHARS))
        }

        try {
            val response = model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.0f
                    topK = 1
                    candidateCount = 1
                    maxOutputTokens = 8
                },
            )
            val output = response.candidates.firstOrNull()?.text?.trim().orEmpty()
            Result.Verdict(parseCode(output, candidates))
        } catch (t: Throwable) {
            Log.w(TAG, "Gemini Nano inference failed", t)
            Result.Error(t)
        }
    }

    private fun parseCode(output: String, candidates: List<String>): String {
        if (output.equals("none", ignoreCase = true)) return Articles.NONE
        // Accept a candidate only when its complete code occurs in the model
        // output. This prevents the model from inventing a code outside the
        // trigger-produced set.
        for (code in candidates) {
            val pattern = Regex("(?<![\\d.])${Regex.escape(code)}(?![\\d.])")
            if (pattern.containsMatchIn(output)) return code
        }
        return Articles.NONE
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun close() {
        cancel()
        scope.cancel()
    }

    fun launchAnalyze(text: String, onResult: (Result) -> Unit) {
        cancel()
        job = scope.launch {
            val result = analyze(text)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }
}
