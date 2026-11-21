package net.x3dp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 常駐して X3 と接続を維持し、ボタン(verb)を受けたら
 * 「ジェスチャ注入 → 再キャプチャ → 転送」を回す。
 */
class DisplayService : Service() {

    private lateinit var client: X3Client
    private var ble: BleClient? = null
    @Volatile private var lastPacket: ByteArray? = null
    private var resendThread: HandlerThread? = null
    private var resendHandler: Handler? = null
    private var resendCount = 0
    private var lastResendAt = 0L
    private var verbThread: HandlerThread? = null
    private var verbHandler: Handler? = null
    private var ioHandler: Handler? = null
    private lateinit var actions: ActionTable
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null

    @Volatile private var running = false

    @Volatile private var seq = 0

    override fun onCreate() {
        super.onCreate()
        client = X3Client(this)
        actions = ActionTable(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PUSH -> {
                // startForegroundService() で起動されるので、5秒以内に必ず startForeground() を呼ぶ。
                // 呼ばないと ForegroundServiceDidNotStartInTimeException でプロセスごと落ちる。
                startForegroundCompat()
                pushOnce()
                ui.postDelayed({ if (!running) stopSelf() }, 4000)
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        if (!running) startLoop()
        return START_STICKY
    }

    /** 画面に出せないイベントをアプリUIへ渡すための簡易ログ。 */
    private fun note(msg: String) {
        Log.i(TAG, msg)
        val sp = getSharedPreferences("x3dp", MODE_PRIVATE)
        val stamped = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg
        val old = sp.getString("eventLog", "") ?: ""
        val lines = (stamped + "\n" + old).lines().take(25).joinToString("\n")
        sp.edit().putString("eventLog", lines).apply()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "X3DP", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DisplayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = androidx.core.app.NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("X3DP")
            .setContentText("X3 からの操作を待機中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startLoop() {
        running = true
        isRunning = true
        val cfg = Config(this)
        val t = HandlerThread("x3dp-io").also { it.start() }
        thread = t
        Handler(t.looper).post { ioLoop(cfg) }
    }

    private fun ioLoop(cfg: Config) {
        if (cfg.transport == "ble") {
            ioLoopBle(cfg)
            return
        }
        while (running) {
            if (!client.isConnected()) {
                val err = client.connect(cfg.host, cfg.port)
                if (err != null) {
                    Log.w(TAG, "connect failed: $err")
                    Thread.sleep(1500)
                    continue
                }
                note("connected ${cfg.host}:${cfg.port}")
                client.send(byteArrayOf(0x4B))     // 'K' = 準備完了
                pushFrame(cfg)                      // 接続直後に1枚送る
            }
            val verb = client.readVerb()            // ボタン待ち(ブロック)
            if (verb >= 0) note("verb 0x" + Integer.toHexString(verb))
            if (verb < 0) {
                Log.w(TAG, "disconnected")
                client.close()
                Thread.sleep(800)
                continue
            }
            handleVerb(cfg, verb)
        }
    }

    /** BLE トランスポートのループ。verb はコールバックで届く。 */
    private fun ioLoopBle(cfg: Config) {
        if (verbHandler == null) {
            verbThread = HandlerThread("x3dp-verb").also { it.start() }
            verbHandler = Handler(verbThread!!.looper)
        }
        if (resendHandler == null) {
            resendThread = HandlerThread("x3dp-resend").also { it.start() }
            resendHandler = Handler(resendThread!!.looper)
        }
        val b = BleClient(this)
        ble = b
        b.onVerb = { verb ->
            if (verb == 0x52) {                    // 'R' = X3が溢れ/連打で捨てた。再送する
                val now = System.currentTimeMillis()
                if (now - lastResendAt > 3000) resendCount = 0
                lastResendAt = now
                resendCount++
                val pkt = lastPacket
                if (resendCount > 8) {
                    note("resend skipped (burst)")
                } else if (pkt != null) {
                    // BLEのコールバックを塞がないよう、別スレッドで待ってから送る
                    resendHandler?.post {
                        Thread.sleep(700)
                        val e = b.send(pkt)
                        note("resend " + (e ?: "OK"))
                    }
                }
            } else {
                note("verb 0x" + Integer.toHexString(verb))
                verbHandler?.post { if (running) handleVerb(cfg, verb) }
            }
        }
        while (running) {
            val e = b.connect()
            if (e != null) {
                note("ble: $e")
                Thread.sleep(1500)
                continue
            }
            note("ble connected mtu=" + b.mtu)
            b.send(byteArrayOf(0x4B))          // 'K' = 準備完了をX3へ通知
            note("sent K (ready)")
            pushFrame(cfg)                    // 接続直後に1枚
            while (running && b.isConnected()) {
                Thread.sleep(200)             // verb はコールバックで処理される
            }
            b.close()
            ScreenSetting.restore(this)
            if (running) Thread.sleep(800)
        }
        b.close()
        ble = null
    }

    /** 現在のトランスポートで1パケット送る。戻り値 null=成功 */
    private fun transportSend(pkt: ByteArray): String? {
        val b = ble
        if (b != null && b.isConnected()) return b.send(pkt)
        return client.send(pkt)
    }

    /** verb: 'N'(1) 'P'(2) 'R'(3) 'S'(4) または 0x10+id でカスタムアクション */
    private fun handleVerb(cfg: Config, verb: Int) {
        val actionId = when (verb.toChar()) {
            'N' -> 1
            'P' -> 2
            'R' -> 4
            'S' -> { running = false; stopSelf(); return }
            'W' -> {
                wakeAndLaunch(cfg)
                if (cfg.autoContinuous && !running) startLoop()
                return
            }
            else -> if (verb >= 0x10) verb - 0x10 else -1
        }
        val action = actions.byId(actionId)
        if (action != null) perform(action)
        pushFrame(cfg)
    }

    /** 画面点灯 → 対象アプリ起動 → 少し待って1枚送る */
    private fun wakeAndLaunch(cfg: Config) {
        val pkg = cfg.launchPkg
        note("wake: start")
        if (cfg.dimScreen) {
            val de = ScreenSetting.dim(this)
            note(if (de == null) "screen: dim" else "screen: $de")
        }
        try {
            startActivity(android.content.Intent(this, WakeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            note("wake failed: ${e.message}")
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (pkg.isNotBlank()) {
                val it = packageManager.getLaunchIntentForPackage(pkg)
                if (it == null) {
                    note("launch: $pkg が見つかりません")
                } else if (cfg.freeform && ShizukuHelper.granted()) {
                    launchFreeform(pkg, it.component?.flattenToString(), cfg.winBounds)
                } else {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    try {
                        if (cfg.freeform) {
                            // フリーフォームが有効なら launchBounds だけでポップアップになる
                            val b = boundsOf(cfg.winBounds)
                            val opts = android.app.ActivityOptions.makeBasic()
                                .setLaunchBounds(b)
                                .toBundle()
                            startActivity(it, opts)
                            note("launch: $pkg (bounds ${b.left},${b.top},${b.right},${b.bottom})")
                        } else {
                            startActivity(it)
                            note("launch: $pkg (fullscreen)")
                        }
                    } catch (e: Throwable) {
                        note("launch failed: ${e.message}")
                    }
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({ pushFrame(cfg) }, 1200)
        }, 800)
    }

    /** "l,t,r,b"(px) か空。空なら画面から2:3で計算 */
    private fun boundsOf(override: String): android.graphics.Rect {
        val v = override.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (v.size == 4) return android.graphics.Rect(v[0], v[1], v[2], v[3])
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val ww = w * 2 / 3
        val hh = (ww * 2).coerceAtMost(h * 9 / 10)
        val l = (w - ww) / 2
        val top = (h - hh) / 3
        return android.graphics.Rect(l, top, l + ww, top + hh)
    }

    /** Shizuku 経由でフリーフォーム(ポップアップ)起動し、位置とサイズを固定する */
    private fun launchFreeform(pkg: String, component: String?, boundsOverride: String) {
        if (component == null) { note("launch: component 不明"); return }
        val start = ShizukuHelper.exec(
            "am start --windowingMode 5 --activity-clear-task -n $component")
        note("freeform: " + (start?.trim()?.take(60) ?: "実行できません"))
        Thread.sleep(1200)

        // 位置とサイズを決める(指定が無ければ画面から2:3で自動計算)
        val b = boundsOverride.ifBlank {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            val ww = w * 2 / 3
            val hh = (ww * 2).coerceAtMost(h * 9 / 10)
            val l = (w - ww) / 2
            val top = (h - hh) / 3
            "$l,$top,${l + ww},${top + hh}"
        }.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (b.size == 4) {
            val dump = ShizukuHelper.exec("dumpsys activity activities") ?: ""
            val id = Regex("\\* Task\\{#(\\d+)[^\\n]*A=\\d+:" + Regex.escape(pkg)).find(dump)
                ?.groupValues?.get(1)
            if (id != null) {
                val r = ShizukuHelper.exec("am task resize $id ${b[0]} ${b[1]} ${b[2]} ${b[3]}")
                note("freeform resize #$id ${b.joinToString(",")} " + (r?.trim() ?: ""))
            } else {
                note("freeform: taskId 不明")
            }
        }
    }

    private fun perform(action: GestureAction) {
        val svc = CaptureAccessibilityService.instance
        if (svc == null) { note("gesture: accessibility off"); return }

        fun swipeIn(rect: Rect) {
            val h = rect.height()
            val dist = (h * kotlin.math.abs(action.pct) / 100.0).toInt().coerceAtLeast(40)
            val cx = rect.centerX()
            if (action.pct >= 0) {
                // 次へ: 指を上へ動かす
                val from = rect.top + (h * 0.78).toInt()
                val to = (from - dist).coerceAtLeast(rect.top + 8)
                note("scroll(next) $cx,$from -> $to (band $rect)")
                svc.swipe(cx, from, to)
            } else {
                // 前へ: 指を下へ動かす
                val from = rect.top + (h * 0.22).toInt()
                val to = (from + dist).coerceAtMost(rect.bottom - 8)
                note("scroll(prev) $cx,$from -> $to (band $rect)")
                svc.swipe(cx, from, to)
            }
        }

        when (action.type) {
            "scroll" -> {
                val win = svc.appWindowBounds(Config(this).targetPkg)
                if (win != null) {
                    swipeIn(svc.bandOf(win))
                } else {
                    note("gesture: no window (targetPkg='" + Config(this).targetPkg + "')")
                    val dm = resources.displayMetrics
                    swipeIn(Rect(0, 0, dm.widthPixels, dm.heightPixels))
                }
            }
            "center" -> {
                val win = svc.appWindowBounds(Config(this).targetPkg) ?: return
                val b = svc.bandOf(win)
                note("tap center ${b.centerX()},${b.centerY()}")
                svc.tap(b.centerX(), b.centerY())
            }
            "tap" -> {
                val win = svc.appWindowBounds(Config(this).targetPkg) ?: return
                val b = svc.bandOf(win)
                val x = b.left + (b.width() * action.xPct / 100)
                val y = b.top + (b.height() * action.yPct / 100)
                note("tap $x,$y (band $b)")
                svc.tap(x, y)
            }
            else -> {}
        }
        Thread.sleep(320)   // ジェスチャ完了待ち
    }

    fun pushOnce() {
        val t = HandlerThread("x3dp-push").also { it.start() }
        Handler(t.looper).post {
            try {
                val cfg = Config(this)
                if (cfg.transport == "ble") {
                    if (ble == null) ble = BleClient(this@DisplayService)
                    val bb = ble!!
                    if (!bb.isConnected()) {
                        val e0 = bb.connect()
                        if (e0 != null) { note("ble: $e0"); return@post }
                    }
                    pushFrame(cfg)
                    return@post
                }
                // 半開きのソケットが残っていると isConnected() が true のまま送信が
                // 無音で失敗する。1枚送信のときは必ず張り直す。
                client.close()
                val err = client.connect(cfg.host, cfg.port)
                if (err != null) { note(err); return@post }
                pushFrame(cfg)
            } catch (e: Throwable) {
                Log.e(TAG, "pushOnce failed", e)
            } finally {
                t.quitSafely()
            }
        }
    }

    private fun pushFrame(cfg: Config) {
        val svc = CaptureAccessibilityService.instance
        if (svc == null) { note("accessibility service not enabled"); return }
        ui.post {
            svc.capture { bmp ->
                if (bmp == null) {
                    note("capture returned null (accessibility off?)")
                    return@capture
                }
                val win = svc.appWindowBounds(cfg.targetPkg)
                val crop: Rect = if (win != null) svc.bandOf(win) else cfg.bandCrop(bmp.width, bmp.height)
                note("capture ${bmp.width}x${bmp.height} crop=$crop win=" + svc.lastWindowInfo)
                // ソケット送信は必ずワーカースレッドで(メインスレッドだと NetworkOnMainThreadException)
                Thread({
                    try {
                        val dbg: ((android.graphics.Bitmap) -> Unit)? =
                            if (cfg.saveDebug) { b -> saveDebugImage(b) } else null
                        val pkt = FrameEncoder.encode(bmp, crop, cfg.mode, cfg.threshold, cfg.ditherMode, ++seq, dbg,
                            if (cfg.cleanRefresh) 1 else 0)
                        if (pkt.isEmpty()) {
                            note("encode returned empty crop=$crop")
                        } else {
                            lastPacket = pkt
                            val e = transportSend(pkt)
                            note(if (e == null) "sent ${pkt.size}B ${ble?.lastSendMs ?: 0}ms" else e)
                        }
                    } catch (t: Throwable) {
                        note("push failed: " + Log.getStackTraceString(t).take(600))
                    } finally {
                        bmp.recycle()
                    }
                }, "x3dp-push").start()
            }
        }
    }

    /** 実際にX3へ送る物理レイアウト画像を Pictures/X3DP に保存する。 */
    private fun saveDebugImage(bmp: android.graphics.Bitmap) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "x3dp_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/X3DP")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            note("debug saved: Pictures/X3DP (${bmp.width}x${bmp.height})")
        } catch (t: Throwable) {
            note("debug save failed: ${t.message}")
        }
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        ScreenSetting.restore(this)
        client.close()
        ble?.close()
        ble = null
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "X3DP"
        const val CH_ID = "x3dp"
        const val NOTIF_ID = 42
        const val ACTION_START = "net.x3dp.START"
        const val ACTION_STOP = "net.x3dp.STOP"
        const val ACTION_PUSH = "net.x3dp.PUSH"

        /** UIから参照する稼働状態 */
        @Volatile var isRunning = false
            private set
    }
}
