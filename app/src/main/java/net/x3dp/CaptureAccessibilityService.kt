package net.x3dp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 画面キャプチャ(takeScreenshot)とジェスチャ注入(dispatchGesture)の両方を担う。
 * どちらも root 不要。
 */
class CaptureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // XML の flagRequestFilterKeyEvents が効かない端末があるので実行時にも立てる
        watchdog.removeCallbacks(watchdogTask)
        watchdog.postDelayed(watchdogTask, 2000)
        try {
            val info = serviceInfo
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (e: Throwable) { /* ignore */ }
    }

    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogTask = object : Runnable {
        override fun run() {
            // 連続モードが落ちていたら起こす(ユーザー補助はOSに常駐させられるので番人に最適)
            val cfg = Config(this@CaptureAccessibilityService)
            if (cfg.autoContinuous && !DisplayService.isRunning) {
                try {
                    val i = android.content.Intent(this@CaptureAccessibilityService,
                        DisplayService::class.java).setAction(DisplayService.ACTION_START)
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        startForegroundService(i) else startService(i)
                } catch (e: Throwable) {
                    // 起動制限に引っかかった場合は次回に期待
                }
            }
            watchdog.postDelayed(this, 5000)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        watchdog.removeCallbacks(watchdogTask)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 現在の画面を取得。API 30+ の takeScreenshot は ~333ms のレート制限あり。 */
    fun capture(callback: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) { null } finally { hb.close() }
                    callback(bmp)
                }
                override fun onFailure(errorCode: Int) { callback(null) }
            })
    }

    /**
     * 転送対象のアプリウィンドウを返す。
     * TYPE_APPLICATION のみを対象にし、自分自身のウィンドウ(トースト等)は除外する。
     * targetPkg が指定されていればそのパッケージのみ、無指定なら面積が最大のもの。
     * (以前は「最小のウィンドウ」を選んでいたため、ステータスバーを掴んでいた)
     */
    fun appWindowBounds(targetPkg: String?): Rect? {
        val wins = try { windows } catch (e: Exception) { null } ?: return null
        val mine = packageName
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        data class C(val rect: Rect, val area: Long, val floating: Boolean, val pkg: String)

        val candidates = wins.mapNotNull { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val b = Rect()
            w.getBoundsInScreen(b)
            val pkg = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            if (pkg == null || pkg == mine) return@mapNotNull null
            if (!targetPkg.isNullOrBlank() && !pkg.contains(targetPkg)) return@mapNotNull null
            if (b.width() < 200 || b.height() < 200) return@mapNotNull null
            val area = b.width().toLong() * b.height()
            // 画面の92%未満なら「浮いている(ポップアップ)」とみなす
            C(b, area, area < screenArea * 92 / 100, pkg)
        }
        if (candidates.isEmpty()) {
            lastWindowInfo = "no candidate"
            return null
        }
        // ポップアップ(小さい浮きウィンドウ)を優先。無ければ最大のウィンドウ。
        val floating = candidates.filter { it.floating }
        val pick = (if (floating.isNotEmpty()) floating.maxByOrNull { it.area }
                    else candidates.maxByOrNull { it.area })!!
        lastWindowInfo = "${pick.pkg} ${pick.rect} floating=${pick.floating}"
        return pick.rect
    }

    /** ウィンドウから 2:3 (528x792) の帯を切り出す。スクロール前提。 */
    fun bandOf(win: Rect): Rect {
        val h = (win.width() * 792) / 528
        val bottom = if (win.top + h > win.bottom) win.bottom else win.top + h
        return Rect(win.left, win.top, win.right, bottom)
    }

    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(path, 0L, 60L)
    }

    fun swipe(x: Int, yFrom: Int, yTo: Int, durationMs: Long = 260L) {
        val path = Path().apply {
            moveTo(x.toFloat(), yFrom.toFloat())
            lineTo(x.toFloat(), yTo.toFloat())
        }
        dispatch(path, 0L, durationMs)
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    /** 直近に選んだウィンドウ(ログ用) */
    @Volatile var lastWindowInfo: String = ""

    /** 音量↓で明るさ復帰(連続モードは止めない) */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == android.view.KeyEvent.ACTION_DOWN && ScreenSetting.isDimmed(this)) {
            ScreenSetting.restore(this)
            return true    // イベントは消費(音量は変えない)
        }
        return super.onKeyEvent(event)
    }

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        fun ready(): Boolean = instance != null
    }
}
