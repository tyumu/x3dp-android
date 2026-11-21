package net.x3dp

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku 経由で shell コマンドを実行する小さなヘルパ。 */
object ShizukuHelper {
    const val REQ_CODE = 4101

    fun available(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun request() {
        if (!available()) return
        try {
            if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_CODE)
            }
        } catch (e: Throwable) { /* ignore */ }
    }

    /**
     * 実行して標準出力を返す。失敗時は null。
     * Shizuku.newProcess は 13.x で private 扱いなのでリフレクションで呼ぶ。
     */
    fun exec(cmd: String, timeoutMs: Long = 8000): String? {
        if (!granted()) return null
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (out.isBlank()) err else out
        } catch (e: Throwable) {
            "shizuku exec failed: ${e.message}"
        }
    }
}
