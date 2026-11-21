package net.x3dp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * X3 の BLE(ペリフェラル)へ接続してフレームを流し込む。
 * Nordic UART Service 互換: RX=書き込み、TX=通知。
 * 送信は NO_RESPONSE で詰め、スタックのキューが詰まったら少し待つ。
 */
@SuppressLint("MissingPermission")
class BleClient(private val ctx: Context) {

    companion object {
        val SVC: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    var onVerb: ((Int) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile var mtu = 23
        private set
    private var connectLatch: CountDownLatch? = null
    private var discoverLatch: CountDownLatch? = null
    private var mtuLatch: CountDownLatch? = null
    private var lastError: String? = null

    private val queue = ArrayDeque<ByteArray>()
    @Volatile private var writerRunning = false
    private var writer: Thread? = null
    private var sendLatch: CountDownLatch? = null

    fun isConnected(): Boolean = gatt?.device != null && connected

    @Volatile private var connected = false

    /** スキャン→接続→サービス探索→MTU要求→通知有効化。戻り値 null=成功 */
    fun connect(timeoutMs: Int = 20000): String? {
        close()
        val mgr = ctx.getSystemService(BluetoothManager::class.java) ?: return "BluetoothManager なし"
        val adapter: BluetoothAdapter = mgr.adapter ?: return "Bluetooth アダプタなし"
        if (!adapter.isEnabled) return "Bluetooth がOFFです"

        val scanner = adapter.bluetoothLeScanner ?: return "BLEスキャナなし"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val found = CountDownLatch(1)
        var device: android.bluetooth.BluetoothDevice? = null

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try { result.scanRecord?.deviceName ?: result.device.name } catch (e: SecurityException) { null }
                // サービスUUIDで来ていれば対象。名前は目視用。
                device = result.device
                lastError = "found ${name ?: result.device.address}"
                found.countDown()
            }
            override fun onScanFailed(errorCode: Int) { lastError = "scan failed $errorCode"; found.countDown() }
        }
        scanner.startScan(listOf(filter), settings, cb)
        try {
            if (!found.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) || device == null) {
                return lastError ?: "X3が見つかりません(スキャンタイムアウト)"
            }
            scanner.stopScan(cb)
            connectLatch = CountDownLatch(1)
            gatt = device!!.connectGatt(ctx, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            if (!(connectLatch?.await(10, TimeUnit.SECONDS) ?: false)) return "接続タイムアウト"
            if (!connected) return lastError ?: "接続失敗"

            // Smart Lock の信頼できるデバイスとして登録できるよう、先にペアリングする
            ensureBonded(device!!)
            discoverLatch = CountDownLatch(1)
            gatt?.discoverServices()
            if (!(discoverLatch?.await(10, TimeUnit.SECONDS) ?: false)) return "サービス探索タイムアウト"

            mtuLatch = CountDownLatch(1)
            gatt?.requestMtu(517)
            mtuLatch?.await(3, TimeUnit.SECONDS)
            return null
        } finally {
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }
    }

    /** ボンドが無ければ作成し、完了まで最大20秒待つ */
    private fun ensureBonded(dev: android.bluetooth.BluetoothDevice) {
        if (dev.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) return
        try {
            if (dev.bondState != android.bluetooth.BluetoothDevice.BOND_BONDING) dev.createBond()
        } catch (e: Exception) {
            lastError = "createBond 失敗: ${e.message}"
            return
        }
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            when (dev.bondState) {
                android.bluetooth.BluetoothDevice.BOND_BONDED -> return
                android.bluetooth.BluetoothDevice.BOND_NONE -> return   // 拒否された
            }
            Thread.sleep(200)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connectLatch?.countDown()
            } else {
                connected = false
                if (status != BluetoothGatt.GATT_SUCCESS) lastError = "gatt status $status"
                connectLatch?.countDown()
                discoverLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val svc = g.getService(SVC)
                rxChar = svc?.getCharacteristic(RX)
                val tx = svc?.getCharacteristic(TX)
                if (tx != null) {
                    g.setCharacteristicNotification(tx, true)
                    val d = tx.getDescriptor(CCCD)
                    if (d != null) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(d)
                        }
                    }
                }
            } else {
                lastError = "discover status $status"
            }
            discoverLatch?.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = mtuValue
            mtuLatch?.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            value.forEach { onVerb?.invoke(it.toInt() and 0xFF) }
        }

        @Deprecated("API<33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            v.forEach { onVerb?.invoke(it.toInt() and 0xFF) }
        }
    }

    /** 1パケット送出。戻り値 null=成功 */
    fun send(packet: ByteArray, timeoutMs: Long = 15000): String? {
        val g = gatt ?: return "未接続"
        val ch = rxChar ?: return "RX特性なし"
        if (!connected) return "切断済み"
        // 244B 以下に抑える。これを超えると LL 層で断片化し、X3側の受信バッファが溢れて
        // ストリームがずれる(実測: MTU517で514B書き込み→縦縞の壊れた画像)。
        val chunkSize = (mtu - 3).coerceAtLeast(20).coerceAtMost(244)
        queue.clear()
        var off = 0
        while (off < packet.size) {
            val n = minOf(chunkSize, packet.size - off)
            queue.add(packet.copyOfRange(off, off + n))
            off += n
        }
        val started = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        sendLatch = latch
        lastError = null
        if (!writerRunning) {
            writerRunning = true
            writer = Thread({
                while (writerRunning) {
                    val item = synchronized(queue) { queue.pollFirst() }
                    if (item == null) {
                        sendSettle()
                        Thread.sleep(2)
                        continue
                    }
                    val ok = try {
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeCharacteristic(ch, item, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                                BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            ch.value = item
                            @Suppress("DEPRECATION")
                            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(ch)
                        }
                    } catch (e: Exception) {
                        lastError = e.message; false
                    }
                    if (!ok) Thread.sleep(1) else Thread.sleep(1)
                }
            }, "x3dp-ble-writer")
            writer?.start()
        }
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        lastSendMs = System.currentTimeMillis() - started
        return when {
            lastError != null -> lastError
            !done -> "BLE送信タイムアウト"
            else -> null
        }
    }

    private fun sendSettle() {
        if (synchronized(queue) { queue.isEmpty() }) sendLatch?.countDown()
    }

    /** 直近の送信の所要時間(ms)と MTU。ログ用 */
    var lastSendMs: Long = 0
        private set

    fun close() {
        writerRunning = false
        try { writer?.join(200) } catch (_: Exception) {}
        writer = null
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        connected = false
        sendLatch?.countDown()
    }
}
