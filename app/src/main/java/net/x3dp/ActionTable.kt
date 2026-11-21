package net.x3dp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** X3 のボタン(verb) → 端末操作の対応表。アプリ内で編集できる。 */
data class GestureAction(
    val id: Int,
    val name: String,
    val type: String,      // "scroll" | "tap" | "center" | "recapture"
    val pct: Int = 0,      // scroll: 画面高の何%動かすか(正=下スクロール)
    val xPct: Int = 50,    // tap: 対象ウィンドウ内の相対位置
    val yPct: Int = 50,
)

class ActionTable(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("x3dp", Context.MODE_PRIVATE)

    fun load(): List<GestureAction> {
        if (prefs.getInt("actionsVersion", 0) < 2) return defaults()
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GestureAction(
                    id = o.optInt("id", i),
                    name = o.optString("name", "action$i"),
                    type = o.optString("type", "recapture"),
                    pct = o.optInt("pct", 0),
                    xPct = o.optInt("xPct", 50),
                    yPct = o.optInt("yPct", 50),
                )
            }
        } catch (e: Exception) { defaults() }
    }

    fun save(list: List<GestureAction>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("type", it.type)
                put("pct", it.pct); put("xPct", it.xPct); put("yPct", it.yPct)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).putInt("actionsVersion", 2).apply()
    }

    fun byId(id: Int): GestureAction? = load().firstOrNull { it.id == id }

    /** pct>0 = 次へ(指を上へ), pct<0 = 前へ(指を下へ)。量は帯の高さに対する%。 */
    fun defaults(): List<GestureAction> = listOf(
        GestureAction(1, "次へ", "scroll", pct = 55),
        GestureAction(2, "前へ", "scroll", pct = -55),
        GestureAction(3, "中央タップ", "center"),
        GestureAction(4, "再取得", "recapture"),
    )

    companion object { private const val KEY = "actions" }
}
