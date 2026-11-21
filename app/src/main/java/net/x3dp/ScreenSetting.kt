package net.x3dp

import android.content.Context
import android.provider.Settings

/** 画面の明るさと消灯時間を退避して 0 にする(要 WRITE_SETTINGS)。 */
object ScreenSetting {
    private const val PREFS = "x3dp_brightness"

    fun canWrite(ctx: Context): Boolean = try {
        Settings.System.canWrite(ctx)
    } catch (e: Throwable) { false }

    fun isDimmed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dimmed", false)

    /** 明るさ0(実質最小)+消灯させない。戻り値 null=成功 */
    fun dim(ctx: Context): String? {
        if (!canWrite(ctx)) return "WRITE_SETTINGS 未許可"
        if (isDimmed(ctx)) return null
        val cr = ctx.contentResolver
        return try {
            val br = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("brightness", br).putInt("mode", mode).putInt("timeout", timeout)
                .putBoolean("dimmed", true).apply()
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30 * 60 * 1000)
            null
        } catch (e: Throwable) {
            "dim failed: ${e.message}"
        }
    }

    /** 元に戻す。戻り値 null=成功 */
    fun restore(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean("dimmed", false)) return null
        if (!canWrite(ctx)) return "WRITE_SETTINGS 未許可"
        val cr = ctx.contentResolver
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, sp.getInt("brightness", 128))
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, sp.getInt("mode", 1))
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, sp.getInt("timeout", 30000))
            sp.edit().putBoolean("dimmed", false).apply()
            null
        } catch (e: Throwable) {
            "restore failed: ${e.message}"
        }
    }
}
