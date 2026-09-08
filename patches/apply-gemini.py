#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

def fail(msg):
    print(f'ERROR: {msg}', file=sys.stderr)
    sys.exit(1)

gradle = ROOT / 'app/build.gradle.kts'
ime = ROOT / 'app/src/main/java/dev/souchastnik/ime/SouchastnikIME.kt'
gemini = ROOT / 'app/src/main/java/dev/souchastnik/ai/GeminiNanoClient.kt'

g = gradle.read_text(encoding='utf-8')
dep_marker = '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")'
dep = '    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")'
if dep not in g:
    if dep_marker not in g:
        fail('Gradle dependency insertion point not found')
    g = g.replace(dep_marker, dep_marker + '\n' + dep, 1)
    gradle.write_text(g, encoding='utf-8')

GEMINI_SOURCE = r'''package dev.souchastnik.ai

import com.google.mlkit.genai.prompt.DownloadStatus
import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Triggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Gemini Nano client for the IME process.
 *
 * Prompt API is backed by Android AICore and runs on-device. The client is
 * intentionally kept out of EngineService (:engine): GenAI inference has
 * foreground-use restrictions, so calls are made from the IME process while
 * the keyboard is active.
 */
class GeminiNanoClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = Generation.getClient()

    @Volatile
    private var available = false

    var onReady: (() -> Unit)? = null
    var onUnavailable: (() -> Unit)? = null
    var onChecking: (() -> Unit)? = null
    var onResult: ((String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    fun prepare() {
        scope.launch {
            onChecking?.invoke()
            try {
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> becomeReady()
                    FeatureStatus.DOWNLOADABLE -> {
                        model.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadStarted,
                                is DownloadStatus.DownloadProgress -> onChecking?.invoke()
                                DownloadStatus.DownloadCompleted -> becomeReady()
                                is DownloadStatus.DownloadFailed -> becomeUnavailable()
                            }
                        }
                    }
                    FeatureStatus.DOWNLOADING -> waitUntilAvailable()
                    FeatureStatus.UNAVAILABLE -> becomeUnavailable()
                }
            } catch (t: Throwable) {
                available = false
                onError?.invoke(t)
                onUnavailable?.invoke()
            }
        }
    }

    fun analyze(text: String) {
        if (!available) return

        scope.launch(Dispatchers.Default) {
            try {
                val match = Triggers.match(text)
                if (match.codes.isEmpty()) {
                    onResult?.invoke(Articles.NONE)
                    return@launch
                }

                val candidates = match.codes.distinct()
                val candidateTable = candidates.joinToString("\n") { code ->
                    Articles[code]?.let { article -> "$code — ${article.title}" } ?: code
                }

                val prompt = buildString {
                    appendLine("Ты классификатор юридических рисков.")
                    appendLine("Верни РОВНО ОДИН код из списка ниже или NONE.")
                    appendLine("Никаких пояснений, markdown или других слов.")
                    appendLine()
                    appendLine("Кандидаты:")
                    appendLine(candidateTable)
                    appendLine("NONE")
                    appendLine()
                    appendLine("Текст:")
                    appendLine(text)
                    appendLine()
                    append("Ответ:")
                }

                val request = generateContentRequest(TextPart(prompt)) {
                    temperature = 0.0f
                    topK = 1
                    candidateCount = 1
                    maxOutputTokens = 8
                }

                val response = model.generateContent(request)
                val raw = response.candidates.firstOrNull()?.text
                onResult?.invoke(parseCode(raw, candidates))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                onError?.invoke(t)
            }
        }
    }

    fun close() {
        available = false
        scope.cancel()
    }

    private fun becomeReady() {
        available = true
        onReady?.invoke()
    }

    private fun becomeUnavailable() {
        available = false
        onUnavailable?.invoke()
    }

    private suspend fun waitUntilAvailable() {
        repeat(90) {
            delay(2000)
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    becomeReady()
                    return
                }
                FeatureStatus.UNAVAILABLE -> {
                    becomeUnavailable()
                    return
                }
                else -> Unit
            }
        }
        becomeUnavailable()
    }

    private fun parseCode(raw: String?, candidates: List<String>): String {
        val normalized = raw?.trim()?.replace("`", "")?.replace(".", "")?.uppercase()
            ?: return Articles.NONE
        if (normalized == Articles.NONE.uppercase()) return Articles.NONE
        return candidates.firstOrNull { it.uppercase() == normalized } ?: Articles.NONE
    }
}
'''

gemini.parent.mkdir(parents=True, exist_ok=True)
#if gemini.exists():
#    current = gemini.read_text(encoding='utf-8')
#    if current != GEMINI_SOURCE:
#        fail('GeminiNanoClient.kt already exists with different contents')
#else:
gemini.write_text(GEMINI_SOURCE, encoding='utf-8')

s = ime.read_text(encoding='utf-8')

def replace_once(old, new, label):
    global s
    if old not in s:
        fail(f'IME patch block not found: {label}')
    s = s.replace(old, new, 1)

if 'import dev.souchastnik.ai.GeminiNanoClient' not in s:
    replace_once(
        'import dev.souchastnik.data.Prefs\nimport dev.souchastnik.engine.EngineClient',
        'import dev.souchastnik.ai.GeminiNanoClient\nimport dev.souchastnik.data.Prefs\nimport dev.souchastnik.engine.EngineClient',
        'imports')

if 'private var gemini: GeminiNanoClient?' not in s:
    replace_once(
        '    private var engine: EngineClient? = null\n\n    /** Пароли',
        '    private var engine: EngineClient? = null\n    private var gemini: GeminiNanoClient? = null\n    private var geminiReady = false\n    private var geminiFallback = false\n\n    /** Пароли',
        'fields')

if 'gemini = GeminiNanoClient()' not in s:
    replace_once(
'''    override fun onCreate() {
        super.onCreate()
        Articles.load(this)
        Agents.load(this)
    }''',
'''    override fun onCreate() {
        super.onCreate()
        Articles.load(this)
        Agents.load(this)

        gemini = GeminiNanoClient().also { client ->
            client.onChecking = {
                main.post { strip.render(EngineClient.State.Loading) }
            }
            client.onReady = {
                main.post {
                    geminiReady = true
                    geminiFallback = false
                    engine?.disconnect()
                    engine = null
                    analyzeCurrent()
                }
            }
            client.onUnavailable = {
                main.post {
                    geminiReady = false
                    geminiFallback = true
                    ensureQwenFallback()
                    analyzeCurrent()
                }
            }
            client.onError = {
                main.post { geminiReady = false }
            }
            client.onResult = { code ->
                main.post {
                    if (!geminiReady) return@post
                    val state = if (code == Articles.NONE) {
                        EngineClient.State.Clean
                    } else {
                        Articles[code]?.let { EngineClient.State.Verdict(it) }
                            ?: EngineClient.State.Clean
                    }
                    strip.render(state)
                }
            }
        }
    }''',
        'onCreate')

if 'gemini?.close()' not in s:
    replace_once(
'''    override fun onDestroy() {
        main.removeCallbacks(idleUnload)
        engine?.disconnect()
        engine = null
        super.onDestroy()
    }''',
'''    override fun onDestroy() {
        main.removeCallbacks(idleUnload)
        gemini?.close()
        gemini = null
        engine?.disconnect()
        engine = null
        super.onDestroy()
    }''',
        'onDestroy')

if 'private fun ensureQwenFallback()' not in s:
    replace_once(
'''    private fun applyEnabled(enabled: Boolean) {
        main.removeCallbacks(idleUnload)
        if (enabled) {
            if (engine == null) {
                engine = EngineClient(this).also { client ->
                    client.onState = { strip.render(it) }
                    client.connect()
                }
            }
            analyzeCurrent()
        } else {
            engine?.disconnect()
            engine = null
            strip.renderOff()
        }
    }''',
'''    private fun applyEnabled(enabled: Boolean) {
        main.removeCallbacks(idleUnload)
        if (enabled) {
            if (!geminiReady && !geminiFallback) gemini?.prepare()
            if (geminiFallback) ensureQwenFallback()
            analyzeCurrent()
        } else {
            geminiReady = false
            geminiFallback = false
            engine?.disconnect()
            engine = null
            strip.renderOff()
        }
    }

    private fun ensureQwenFallback() {
        if (engine != null) return
        engine = EngineClient(this).also { client ->
            client.onState = { strip.render(it) }
            client.connect()
        }
    }''',
        'applyEnabled')

if 'gemini?.analyze(before)' not in s:
    replace_once(
'''    private fun analyzeCurrent() {
        if (suppressed) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return
        engine?.onTextChanged(before)
    }''',
'''    private fun analyzeCurrent() {
        if (suppressed) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return

        if (geminiReady) {
            gemini?.analyze(before)
        } else if (geminiFallback) {
            engine?.onTextChanged(before)
        }
    }''',
        'analyzeCurrent')

ime.write_text(s, encoding='utf-8')
# ===== Отключаем llama.cpp в CMakeLists.txt =====
cmake_path = ROOT / 'app/src/main/cpp/CMakeLists.txt'
if cmake_path.exists():
    cmake_content = cmake_path.read_text(encoding='utf-8')
    lines = cmake_content.splitlines()
    new_lines = []
    for line in lines:
        if 'add_subdirectory' in line and 'llama.cpp' in line:
            new_lines.append('#' + line)
        elif 'add_library(souchastnik' in line:
            new_lines.append('#' + line)
        elif 'target_link_libraries(souchastnik' in line:
            new_lines.append('#' + line)
        else:
            new_lines.append(line)
    cmake_path.write_text('\n'.join(new_lines), encoding='utf-8')
    print('ℹ️  CMakeLists.txt updated: llama.cpp lines commented out')
else:
    print('⚠️  CMakeLists.txt not found, skipping')
print('Gemini Nano patch applied successfully.')
