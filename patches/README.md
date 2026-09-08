# Gemini Nano patch

Target source commit:

`df86f6ce02daf6daaad416c43839ca707a23e73d`

The patcher is deliberately strict: it refuses to modify another revision and
fails if an expected source block is missing.

Changes:

- adds `com.google.mlkit:genai-prompt:1.0.0-beta4`;
- adds `GeminiNanoClient` using Android AICore / ML Kit Prompt API;
- calls Gemini from the IME process, not `:engine`;
- keeps the existing Qwen/llama.cpp engine as a fallback if Gemini Nano is unavailable;
- keeps the existing trigger pre-filter and closed-set article-code parsing;
- does not add `INTERNET` permission;
- does not bundle Gemini Nano weights in the APK.

The patch does not remove the existing Qwen model/native code.
