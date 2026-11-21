package net.x3dp

import android.content.Context
import android.graphics.Rect

/** アプリ設定。SharedPreferences に保存。 */
class Config(ctx: Context) {
    private val p = ctx.getSharedPreferences("x3dp", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "x3disp.local") ?: "x3disp.local"
        set(v) = p.edit().putString("host", v).apply()

    var port: Int
        get() = p.getInt("port", 5283)
        set(v) = p.edit().putInt("port", v).apply()

    /** 連続モード中は画面を真っ暗にする */
    var dimScreen: Boolean
        get() = p.getBoolean("dimScreen", true)
        set(v) = p.edit().putBoolean("dimScreen", v).apply()

    /** 起動時に連続モードを自動で開始する */
    var autoContinuous: Boolean
        get() = p.getBoolean("autoContinuous", true)
        set(v) = p.edit().putBoolean("autoContinuous", v).apply()

    /** ポップアップ(フリーフォーム)で起動する */
    var freeform: Boolean
        get() = p.getBoolean("freeform", true)
        set(v) = p.edit().putBoolean("freeform", v).apply()

    /** フリーフォームの位置 "l,t,r,b"(px)。空なら自動 */
    var winBounds: String
        get() = p.getString("winBounds", "") ?: ""
        set(v) = p.edit().putString("winBounds", v).apply()

    /** 1タッチ起動で開くアプリのパッケージ名(空なら起動しない) */
    var launchPkg: String
        get() = p.getString("launchPkg", "") ?: ""
        set(v) = p.edit().putString("launchPkg", v).apply()

    /** "wifi" or "ble" */
    var transport: String
        get() = p.getString("transport", "wifi") ?: "wifi"
        set(v) = p.edit().putString("transport", v).apply()

    var mode: Int              // 0=1bit, 1=4gray
        get() = p.getInt("mode", 0)
        set(v) = p.edit().putInt("mode", v).apply()

    var threshold: Int
        get() = p.getInt("threshold", 128)
        set(v) = p.edit().putInt("threshold", v).apply()

    var dither: Boolean
        get() = p.getBoolean("dither", false)
        set(v) = p.edit().putBoolean("dither", v).apply()

    /** 0=なし(単純2値), 1=ブルーノイズ, 2=ハーフトーン(8x8 Bayer + 4段階) */
    var ditherMode: Int
        get() = p.getInt("ditherMode", if (dither) 1 else 0)
        set(v) { p.edit().putInt("ditherMode", v).putBoolean("dither", v != 0).apply() }

    /** PiP など対象アプリを絞るときのパッケージ名。空なら最小ウィンドウを自動選択 */
    var targetPkg: String
        get() = p.getString("targetPkg", "") ?: ""
        set(v) = p.edit().putString("targetPkg", v).apply()

    /** 毎回 HALF(全面波形)で更新する。既定OFF=FAST(黒フラッシュなし) */
    var cleanRefresh: Boolean
        get() = p.getBoolean("cleanRefresh", false)
        set(v) = p.edit().putBoolean("cleanRefresh", v).apply()

    /** 送信画像を Pictures/X3DP に保存する(デバッグ) */
    var saveDebug: Boolean
        get() = p.getBoolean("saveDebug", false)
        set(v) = p.edit().putBoolean("saveDebug", v).apply()

    /** 対象ウィンドウが取れないとき、画面幅いっぱいの 2:3 帯を切り出す */
    fun bandCrop(w: Int, h: Int): Rect {
        val bandH = (w * 792 / 528).coerceAtMost(h)
        val top = ((h - bandH) / 2).coerceAtLeast(0)
        return Rect(0, top, w, top + bandH)
    }
}
