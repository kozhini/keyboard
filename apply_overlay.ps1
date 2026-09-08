$ErrorActionPreference = 'Stop'

$root = Get-Location
$gradle = Join-Path $root 'app/build.gradle.kts'
$ime = Join-Path $root 'app/src/main/java/dev/souchastnik/ime/SouchastnikIME.kt'
$client = Join-Path $root 'app/src/main/java/dev/souchastnik/ai/GeminiNanoClient.kt'

if (!(Test-Path $gradle) -or !(Test-Path $ime)) {
  throw 'Run this script from the root of MShverdiakov/souchastnik.'
}

New-Item -ItemType Directory -Force -Path (Split-Path $client) | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'app/src/main/java/dev/souchastnik/ai/GeminiNanoClient.kt') $client -Force

$g = Get-Content $gradle -Raw
if ($g -notmatch 'com\.google\.mlkit:genai-prompt') {
  $needle = '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")'
  $replacement = $needle + "`r`n    implementation(\"com.google.mlkit:genai-prompt:1.0.0-beta2\")"
  if ($g -notlike "*$needle*") { throw 'Gradle dependency anchor not found.' }
  $g = $g.Replace($needle, $replacement)
  Set-Content -Path $gradle -Value $g -Encoding UTF8
}

$imeText = Get-Content $ime -Raw
if ($imeText -notmatch 'dev\.souchastnik\.ai\.GeminiNanoClient') {
  $imeText = $imeText.Replace('import dev.souchastnik.data.Agents', 'import dev.souchastnik.ai.GeminiNanoClient`r`nimport dev.souchastnik.data.Agents')
  $imeText = $imeText.Replace('    private var engine: EngineClient? = null', '    private var engine: EngineClient? = null`r`n    private var gemini: GeminiNanoClient? = null`r`n    private var geminiRequestId = 0L')
  $imeText = $imeText.Replace('        Articles.load(this)`r`n        Agents.load(this)', '        Articles.load(this)`r`n        Agents.load(this)`r`n        gemini = GeminiNanoClient(this)')
  $imeText = $imeText.Replace('        engine?.disconnect()`r`n        engine = null', '        engine?.disconnect()`r`n        engine = null`r`n        gemini?.close()`r`n        gemini = null')
  $imeText = $imeText.Replace('        engine?.disconnect()`r`n        engine = null`r`n        strip.renderOff()', '        engine?.disconnect()`r`n        engine = null`r`n        gemini?.cancel()`r`n        strip.renderOff()')
  $imeText = $imeText.Replace('        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return`r`n        engine?.onTextChanged(before)', @'
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return
        val text = before.trim()
        if (text.length < 12) {
            gemini?.cancel()
            engine?.onTextChanged("")
            return
        }

        val g = gemini
        if (g == null) {
            engine?.onTextChanged(before)
            return
        }

        val requestId = ++geminiRequestId
        strip.render(VerdictStrip.State.Thinking)
        g.launchAnalyze(before) { result ->
            if (requestId != geminiRequestId) return@launchAnalyze
            when (result) {
                is GeminiNanoClient.Result.Verdict -> {
                    if (result.code == Articles.NONE) {
                        strip.render(VerdictStrip.State.Clean)
                    } else {
                        Articles[result.code]?.let { strip.render(VerdictStrip.State.Verdict(it)) }
                            ?: strip.render(VerdictStrip.State.Clean)
                    }
                }
                is GeminiNanoClient.Result.Unavailable,
                is GeminiNanoClient.Result.Error -> {
                    // Qwen remains the verified fallback path.
                    engine?.onTextChanged(before)
                }
            }
        }
'@)
  Set-Content -Path $ime -Value $imeText -Encoding UTF8
}

Write-Host 'Gemini Nano overlay applied. Existing Qwen/llama.cpp path is retained as fallback.'
