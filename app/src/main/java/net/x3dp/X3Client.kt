package net.x3dp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * X3 との通信。VPN(AdGuard/Tailscale)を避けるため、必ず Wi-Fi の Network にソケットを bind する。
 * 失敗理由を文字列で返すので、アプリの接続テストでそのまま表示できる。
 */
class X3Client(private val ctx: Context) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null

    fun wifiNetwork(): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    /** Wi-Fi の IP と SSID を返す(診断表示用)。 */
    fun wifiInfo(): String {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val ip = wm?.connectionInfo?.ipAddress?.let { v ->
            "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
        } ?: "?"
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid ?: "?"
        val net = if (wifiNetwork() != null) "wifi-network=OK" else "wifi-network=MISSING"
        return "ssid=$ssid ip=$ip $net"
    }

    /** 戻り値: null=成功, それ以外=失敗理由 */
    fun connect(host: String, port: Int, timeoutMs: Int = 3000): String? {
        close()
        val net = wifiNetwork() ?: return "Wi-Fi ネットワークが見つかりません(VPN/機内モード?)"
        val s = Socket()
        try {
            net.bindSocket(s)                        // ← VPN を迂回
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            return "bind 失敗: ${e.message}"
        }
        return try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            socket = s
            input = DataInputStream(s.getInputStream())
            null
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
            "接続失敗 ${host}:${port} -> ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun send(packet: ByteArray): String? {
        val s = socket ?: return "未接続"
        return try {
            s.getOutputStream().write(packet)
            s.getOutputStream().flush()
            null
        } catch (e: IOException) {
            close(); "送信失敗: ${e.message}"
        }
    }

    fun readVerb(): Int {
        val i = input ?: return -1
        return try { i.read() } catch (e: IOException) { -1 }
    }

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null
    }

    data class Found(val host: String, val port: Int, val via: String)

    /** UDP で X3 を探す。ブロードキャストとサブネット宛の両方を試す。 */
    fun discover(timeoutMs: Int = 1200): Pair<Found?, String> {
        val net = wifiNetwork() ?: return null to "Wi-Fi ネットワークなし"
        val targets = mutableListOf<Pair<String, String>>()
        targets += "255.255.255.255" to "broadcast"
        val local = localIp()
        if (local != null) {
            val prefix = local.substringBeforeLast('.')
            targets += "$prefix.255" to "directed($prefix.255)"
        }
        val log = StringBuilder()
        for ((addr, label) in targets) {
            try {
                DatagramSocket().use { sock ->
                    net.bindSocket(sock)
                    sock.broadcast = true
                    sock.soTimeout = timeoutMs
                    val payload = "X3DP?".toByteArray(Charsets.US_ASCII)
                    sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(addr), DISCOVERY_PORT))
                    val buf = ByteArray(64)
                    val resp = DatagramPacket(buf, buf.size)
                    sock.receive(resp)
                    val text = String(resp.data, 0, resp.length, Charsets.US_ASCII).trim()
                    if (text.startsWith("X3DP!")) {
                        return Found(resp.address.hostAddress, text.removePrefix("X3DP!").toIntOrNull() ?: PORT, label) to "OK($label)"
                    }
                }
            } catch (e: Exception) {
                log.append("$label:${e.javaClass.simpleName} ")
            }
        }
        return null to "見つからず [$log]"
    }

    private fun localIp(): String? {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val v = wm.connectionInfo?.ipAddress ?: return null
        if (v == 0) return null
        return "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
    }

    companion object {
        const val DISCOVERY_PORT = 5284
        const val PORT = 5283
    }
}
