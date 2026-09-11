package dev.souchastnik.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small, public prompt examples. Keep these neutral and non-sensitive. */
object Examples {
    private var loaded = false
    private var clean: List<String> = emptyList()
    private var byCode: Map<String, List<String>> = emptyMap()

    fun load(ctx: Context) {
        if (loaded) return

        val json = runCatching {
            ctx.assets.open("examples.json").bufferedReader().use { it.readText() }
        }.getOrElse {
            loaded = true
            return
        }

        val root = JSONObject(json)
        clean = root.optJSONArray("clean").toStringList()

        val examples = root.optJSONObject("examples")
        if (examples != null) {
            val result = LinkedHashMap<String, List<String>>()
            for (code in examples.keys()) {
                result[code] = examples.optJSONArray(code).toStringList()
            }
            byCode = result
        }
        loaded = true
    }

    fun judgeBlock(codes: List<String>, groupClean: List<String>): String {
        val lines = ArrayList<String>()
        for (code in codes) {
            byCode[code].orEmpty().take(2).forEach { lines += "$code: $it" }
        }
        clean.take(4).forEach { lines += "none: $it" }
        groupClean.take(4).forEach { lines += "none: $it" }
        return lines.joinToString("\n")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val result = ArrayList<String>(length())
        for (i in 0 until length()) {
            optString(i).trim().takeIf { it.isNotEmpty() }?.let(result::add)
        }
        return result
    }
}
