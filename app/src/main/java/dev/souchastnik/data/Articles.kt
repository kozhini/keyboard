package dev.souchastnik.data

import android.content.Context
import org.json.JSONObject

data class Article(
    val code: String,
    val act: String,
    val title: String,
    val penalty: String,
    val severity: Int,
) {
    fun strip(): String = "ст. $code $act · $title · $penalty"
}

object Articles {
    const val NONE = "none"

    private var list: List<Article> = emptyList()
    private var byCode: Map<String, Article> = emptyMap()

    fun load(ctx: Context) {
        if (list.isNotEmpty()) return

        val json = ctx.assets.open("articles.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("articles")
        val items = ArrayList<Article>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items += Article(
                code = o.getString("code"),
                act = o.getString("act"),
                title = o.getString("title"),
                penalty = o.getString("penalty"),
                severity = o.getInt("severity"),
            )
        }
        list = items
        byCode = items.associateBy { it.code }
    }

    operator fun get(code: String): Article? = byCode[code]

    fun size(): Int = list.size
}
