package net.x3dp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var actions: ActionTable
    private val pool = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        actions = ActionTable(this)
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) {
            perms += android.Manifest.permission.BLUETOOTH_SCAN
            perms += android.Manifest.permission.BLUETOOTH_CONNECT
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { text = ""; textSize = 15f }
        diag = TextView(this).apply { text = ""; textSize = 12f; setTextIsSelectable(true) }

        val host = EditText(this).apply { setText(cfg.host); hint = "X3のIP or x3disp.local" }
        val port = EditText(this).apply { setText(cfg.port.toString()); hint = "port" }
        val pkg = EditText(this).apply { setText(cfg.targetPkg); hint = "対象パッケージ(空=最小ウィンドウ)" }
        val thr = EditText(this).apply { setText(cfg.threshold.toString()); hint = "threshold 0-255" }
        val gray = CheckBox(this).apply { text = "4階調で送る"; isChecked = cfg.mode == 1 }
        val ditherNames = arrayOf("なし(単純2値)", "スムーズ(ブルーノイズ)", "ハーフトーン(8x8/4段階)")
        val dither = Button(this).apply {
            text = "ディザ: " + ditherNames[cfg.ditherMode.coerceIn(0, 2)]
            setOnClickListener {
                cfg.ditherMode = (cfg.ditherMode + 1) % 3
                text = "ディザ: " + ditherNames[cfg.ditherMode]
            }
        }
        val saveDbg = CheckBox(this).apply { text = "送信画像を保存(Pictures/X3DP)"; isChecked = cfg.saveDebug }
        val cleanRef = CheckBox(this).apply { text = "毎回クリーン更新(HALF・暗転あり)"; isChecked = cfg.cleanRefresh }

        fun save() {
            cfg.host = host.text.toString().trim().ifEmpty { "x3disp.local" }
            cfg.port = port.text.toString().toIntOrNull() ?: 5283
            cfg.targetPkg = pkg.text.toString().trim()
            cfg.threshold = thr.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: 128
            cfg.mode = if (gray.isChecked) 1 else 0
            cfg.saveDebug = saveDbg.isChecked
            cfg.cleanRefresh = cleanRef.isChecked
        }

        fun log(msg: String) {
            ui.post { diag.text = msg + "\n" + diag.text }
        }

        box.addView(status)
        box.addView(label("X3 ホスト"))
        box.addView(host)
        box.addView(label("ポート"))
        box.addView(port)
        box.addView(label("対象パッケージ(任意)"))
        box.addView(pkg)
        box.addView(label("閾値"))
        box.addView(thr)
        box.addView(gray)
        box.addView(dither)
        box.addView(saveDbg)
        box.addView(cleanRef)

        box.addView(Button(this).apply {
            text = "接続: " + if (cfg.transport == "ble") "Bluetooth" else "Wi-Fi"
            setOnClickListener {
                cfg.transport = if (cfg.transport == "ble") "wifi" else "ble"
                text = "接続: " + if (cfg.transport == "ble") "Bluetooth" else "Wi-Fi"
                refresh()
            }
        })

        box.addView(label("1タッチ起動(X3のConfirm長押し)"))
        val launchField = EditText(this).apply {
            setText(cfg.launchPkg)
            hint = "パッケージ名 (例: com.android.chrome)"
            textSize = 13f
        }
        box.addView(launchField)
        box.addView(Button(this).apply {
            text = "インストール済みアプリから選ぶ"
            setOnClickListener {
                val pm = packageManager
                val it = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(it, 0)
                    .map { a -> a.activityInfo.packageName to a.loadLabel(pm).toString() }
                    .distinctBy { a -> a.first }
                    .sortedBy { a -> a.second }
                val labels = apps.map { a -> a.second + "  (" + a.first + ")" }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("起動するアプリ")
                    .setItems(labels) { _, i ->
                        launchField.setText(apps[i].first)
                        cfg.launchPkg = apps[i].first
                    }
                    .show()
            }
        })
        val boundsField = EditText(this).apply {
            setText(cfg.winBounds)
            hint = "フリーフォーム位置 l,t,r,b (空=自動)"
            textSize = 12f
        }
        box.addView(boundsField)
        box.addView(Button(this).apply {
            text = "ポップアップ(フリーフォーム)で起動: " + if (cfg.freeform) "ON" else "OFF"
            setOnClickListener {
                cfg.freeform = !cfg.freeform
                text = "ポップアップ(フリーフォーム)で起動: " + if (cfg.freeform) "ON" else "OFF"
                cfg.winBounds = boundsField.text.toString().trim()
            }
        })
        box.addView(Button(this).apply {
            text = "Shizuku の権限を要求(フリーフォーム/サイズ指定に必要)"
            setOnClickListener {
                if (!ShizukuHelper.available()) {
                    Toast.makeText(this@MainActivity,
                        "Shizukuが起動していません。Shizukuアプリを起動してください", Toast.LENGTH_LONG).show()
                } else {
                    ShizukuHelper.request()
                    Toast.makeText(this@MainActivity, "許可ダイアログを確認してください", Toast.LENGTH_SHORT).show()
                }
            }
        })
        box.addView(Button(this).apply {
            text = "Shizuku の状態を表示"
            setOnClickListener {
                val msg = when {
                    !ShizukuHelper.available() -> "Shizuku 未起動"
                    ShizukuHelper.granted() -> "Shizuku OK(許可済み)"
                    else -> "Shizuku 起動中・未許可"
                }
                log("shizuku: $msg")
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        })

        box.addView(Button(this).apply {
            text = "連続モード中は画面を暗くする: " + if (cfg.dimScreen) "ON" else "OFF"
            setOnClickListener {
                cfg.dimScreen = !cfg.dimScreen
                text = "連続モード中は画面を暗くする: " + if (cfg.dimScreen) "ON" else "OFF"
            }
        })
        box.addView(Button(this).apply {
            text = "明るさ変更を許可(WRITE_SETTINGS)"
            setOnClickListener {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + packageName)))
            }
        })
        box.addView(Button(this).apply {
            text = "起動時に連続モードを自動開始: " + if (cfg.autoContinuous) "ON" else "OFF"
            setOnClickListener {
                cfg.autoContinuous = !cfg.autoContinuous
                text = "起動時に連続モードを自動開始: " + if (cfg.autoContinuous) "ON" else "OFF"
            }
        })

        box.addView(Button(this).apply {
            text = "重ねて表示を許可(バックグラウンド起動に必要)"
            setOnClickListener {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + packageName)))
            }
        })
        box.addView(Button(this).apply {
            text = "電池最適化から除外"
            setOnClickListener {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + packageName)))
            }
        })

        box.addView(Button(this).apply {
            text = "ユーザー補助を開く"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        box.addView(Button(this).apply {
            text = "診断(ネットワーク)"
            setOnClickListener {
                save()
                log("--- 診断 ---")
                if (cfg.transport == "ble") {
                    val ad = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                    val nm = try { ad?.name } catch (e: Exception) { "?" }
                    log("ble adapter: " + (ad?.isEnabled?.toString() ?: "なし") + " name=" + nm)
                    pool.execute {
                        log("capture: " + if (CaptureAccessibilityService.ready()) "ON" else "OFF")
                        val b = BleClient(this@MainActivity)
                        val e = b.connect()
                        log("ble connect: " + (e ?: "OK"))
                        if (e == null) {
                            val bmp = FrameEncoder.testPattern("hello")
                            val pkt = FrameEncoder.encode(bmp, null, 0, 128, cfg.ditherMode, 1)
                            log("ble send test: " + (b.send(pkt) ?: "OK ${pkt.size}B"))
                        }
                        b.close()
                        log("--- 終わり ---")
                    }
                    return@setOnClickListener
                }
                val c = X3Client(this@MainActivity)
                log("wifi: " + c.wifiInfo())
                pool.execute {
                    log("capture: " + if (CaptureAccessibilityService.ready()) "ON" else "OFF")
                    val (found, why) = c.discover()
                    log("discover: " + (found?.let { "${it.host}:${it.port} (${it.via})" } ?: "なし") + " / " + why)
                    val err = c.connect(cfg.host, cfg.port)
                    log("tcp ${cfg.host}:${cfg.port}: " + (err ?: "接続OK"))
                    if (err == null) {
                        val bmp = FrameEncoder.testPattern("hello")
                        val pkt = FrameEncoder.encode(bmp, null, 0, 128, cfg.ditherMode, 1)
                        log("send test pattern: " + (c.send(pkt) ?: "送信OK (${pkt.size}B)"))
                    }
                    c.close()
                    log("--- 終わり ---")
                }
            }
        })

        box.addView(Button(this).apply {
            text = "テストパターン送信(キャプチャ不要)"
            setOnClickListener {
                save()
                if (cfg.transport == "ble") {
                    pool.execute {
                        val b = BleClient(this@MainActivity)
                        val e = b.connect()
                        if (e != null) { log("ble: " + e); return@execute }
                        val bmp = FrameEncoder.testPattern("pattern ${System.currentTimeMillis() % 1000}")
                        val pkt = FrameEncoder.encode(bmp, null, cfg.mode, cfg.threshold, cfg.ditherMode, 1)
                        log("ble test pattern: " + (b.send(pkt) ?: "OK ${pkt.size}B"))
                        b.close()
                    }
                    return@setOnClickListener
                }
                pool.execute {
                    val c = X3Client(this@MainActivity)
                    val err = c.connect(cfg.host, cfg.port)
                    if (err != null) { log(err); return@execute }
                    val bmp = FrameEncoder.testPattern("pattern ${System.currentTimeMillis() % 1000}")
                    val pkt = FrameEncoder.encode(bmp, null, cfg.mode, cfg.threshold, cfg.ditherMode, 1)
                    log("test pattern: " + (c.send(pkt) ?: "OK ${pkt.size}B"))
                    c.close()
                }
            }
        })

        box.addView(Button(this).apply {
            text = "画面を1枚送信"
            setOnClickListener {
                save()
                Toast.makeText(this@MainActivity, "送信中…", Toast.LENGTH_SHORT).show()
                startServiceAction(DisplayService.ACTION_PUSH)
                refresh()
            }
        })

        box.addView(Button(this).apply {
            text = "連続モード開始"
            setOnClickListener {
                save()
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, DisplayService::class.java)
                )
                refresh()
            }
        })

        box.addView(Button(this).apply {
            text = "停止"
            setOnClickListener {
                startService(Intent(this@MainActivity, DisplayService::class.java)
                    .setAction(DisplayService.ACTION_STOP))
                refresh()
            }
        })

        box.addView(Button(this).apply {
            text = "X3を自動検出(UDP)"
            setOnClickListener {
                save()
                pool.execute {
                    val c = X3Client(this@MainActivity)
                    val (found, why) = c.discover()
                    if (found != null) {
                        ui.post {
                            host.setText(found.host)
                            port.setText(found.port.toString())
                        }
                        log("discover OK: ${found.host}:${found.port} via ${found.via}")
                    } else {
                        log("discover: なし / $why")
                    }
                }
            }
        })

        box.addView(Button(this).apply {
            text = "アクション表を既定に戻す"
            setOnClickListener {
                actions.save(actions.defaults())
                Toast.makeText(this@MainActivity, "既定に戻しました", Toast.LENGTH_SHORT).show()
            }
        })

        box.addView(label("ボタン割り当て(数値は%指定)"))
        val current = actions.load()
        val editors = current.map { a ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = TextView(this).apply { text = a.name; textSize = 13f; width = 220 }
            val typeBtn = Button(this).apply {
                var t = a.type
                fun labelOf(x: String) = when (x) {
                    "scroll" -> "スクロール"; "center" -> "中央タップ"; "tap" -> "座標タップ"; else -> "再取得"
                }
                text = labelOf(t)
                setOnClickListener {
                    t = when (t) { "scroll" -> "tap"; "tap" -> "center"; "center" -> "recapture"; else -> "scroll" }
                    text = labelOf(t)
                }
            }
            val pct = EditText(this).apply { setText(a.pct.toString()); hint = "量%"; textSize = 12f; width = 150 }
            val xt = EditText(this).apply { setText(a.xPct.toString()); hint = "x%"; textSize = 12f; width = 120 }
            val yt = EditText(this).apply { setText(a.yPct.toString()); hint = "y%"; textSize = 12f; width = 120 }
            row.addView(name); row.addView(typeBtn); row.addView(pct); row.addView(xt); row.addView(yt)
            box.addView(row)
            Triple(a, typeBtn, Triple(pct, xt, yt))
        }
        box.addView(Button(this).apply {
            text = "ボタン割り当てを保存"
            setOnClickListener {
                val list = editors.map { (a, typeBtn, fields) ->
                    val t = when (typeBtn.text.toString()) {
                        "スクロール" -> "scroll"; "中央タップ" -> "center"; "座標タップ" -> "tap"; else -> "recapture"
                    }
                    GestureAction(
                        id = a.id,
                        name = a.name,
                        type = t,
                        pct = fields.first.text.toString().toIntOrNull() ?: 0,
                        xPct = fields.second.text.toString().toIntOrNull() ?: 50,
                        yPct = fields.third.text.toString().toIntOrNull() ?: 50,
                    )
                }
                actions.save(list)
                Toast.makeText(this@MainActivity, "保存しました", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })
        box.addView(Button(this).apply {
            text = "対象パッケージをクリア(自動判定に戻す)"
            setOnClickListener {
                pkg.setText("")
                cfg.targetPkg = ""
                Toast.makeText(this@MainActivity, "クリアしました", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })

        box.addView(label("ログ"))
        box.addView(diag)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setPadding(0, 24, 0, 4)
    }

    private fun startServiceAction(action: String) {
        ContextCompat.startForegroundService(
            this, Intent(this, DisplayService::class.java).setAction(action)
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
        val ev = getSharedPreferences("x3dp", MODE_PRIVATE).getString("eventLog", null)
        if (ev != null) diag.text = "--- service ---\n" + ev
        val crash = getSharedPreferences("x3dp", MODE_PRIVATE).getString("lastCrash", null)
        if (crash != null) {
            diag.text = "CRASH:\n" + crash.take(1500) + "\n" + diag.text
            getSharedPreferences("x3dp", MODE_PRIVATE).edit().remove("lastCrash").apply()
        }
    }

    /** アプリを開いた時点で連続モードに入る(常時オン運用) */
    private fun autoStartContinuous() {
        if (!cfg.autoContinuous) return
        if (DisplayService.isRunning) return
        try {
            val i = Intent(this, DisplayService::class.java).setAction(DisplayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            status.postDelayed({ refresh() }, 500)
        } catch (e: Throwable) {
            Toast.makeText(this, "autoStart failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        val acc = CaptureAccessibilityService.ready()
        val dm = intArrayOf(0, 1, 2)
        val dn = arrayOf("なし", "スムーズ", "ハーフトーン")
        status.text = buildString {
            append(if (acc) "ユーザー補助: ON" else "ユーザー補助: OFF")
            append(if (DisplayService.isRunning) " | 連続: ON" else " | 連続: OFF")
            append(" | ").append(if (cfg.transport == "ble") "BLE" else cfg.host + ":" + cfg.port)
            append(" | ディザ: ").append(dn[cfg.ditherMode.coerceIn(0, 2)])
            append(" | 対象: ").append(if (cfg.targetPkg.isBlank()) "(自動)" else cfg.targetPkg)
        }
    }
}
