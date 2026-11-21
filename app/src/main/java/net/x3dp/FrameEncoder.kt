package net.x3dp

import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * X3DP フレームエンコーダ。
 *
 * X3 のパネルは物理的に 792x528(横長)で、アプリ側の論理座標 528x792 は
 * renderer の Portrait 変換 (phyX = y, phyY = PH-1-x) で回転して格納される。
 * 端末側で回転すると1画素ずつずれるリスクがあるので、**送信側で物理レイアウトに
 * 変換**して送り、ファームは framebuffer にそのまま memcpy する。
 */
object FrameEncoder {
    const val LW = 528
    const val LH = 792
    const val W = 792   // physical
    const val H = 528   // physical
    private const val MAGIC = "X3DP"
    private const val VERSION = 1

    /** ネットワーク疎通確認用の論理 528x792 画像(キャプチャ権限不要)。 */
    fun testPattern(label: String): Bitmap {
        val bmp = Bitmap.createBitmap(LW, LH, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = true }
        p.textSize = 42f
        c.drawText("X3DP TEST", 40f, 120f, p)
        p.textSize = 28f
        c.drawText(label.take(30), 40f, 180f, p)
        for (i in 0 until 12) {
            c.drawRect(40f + i * 38f, 220f + i * 38f, 60f + i * 38f, 240f + i * 38f, p)
        }
        p.textSize = 22f
        c.drawText("if you can read this, the link works", 40f, 740f, p)
        p.style = android.graphics.Paint.Style.STROKE
        c.drawRect(2f, 2f, LW - 3f, LH - 3f, p)
        return bmp
    }

    fun encode(
        src: Bitmap,
        crop: Rect?,
        mode: Int,          // 0 = 1bit, 1 = 4gray
        threshold: Int,
        ditherMode: Int,          // 0=off, 1=blue noise, 2=halftone
        seq: Int,
        onDebug: ((Bitmap) -> Unit)? = null,
        flags: Int = 0,
    ): ByteArray {
        val r = crop ?: Rect(0, 0, src.width, src.height)
        val safe = Rect(
            r.left.coerceIn(0, src.width - 1),
            r.top.coerceIn(0, src.height - 1),
            r.right.coerceIn(1, src.width),
            r.bottom.coerceIn(1, src.height),
        )
        if (safe.width() < 8 || safe.height() < 8) return ByteArray(0)

        val piece = Bitmap.createBitmap(src, safe.left, safe.top, safe.width(), safe.height())
        val scaled = Bitmap.createScaledBitmap(piece, LW, LH, true)
        if (piece !== src) piece.recycle()
        val pixels = IntArray(LW * LH)
        scaled.getPixels(pixels, 0, LW, 0, 0, LW, LH)
        scaled.recycle()

        val gray = toPhysicalGray(pixels)
        val whiteMask = when (ditherMode) {
            1 -> BlueNoise.threshold(gray, W, H, 238, 40)
            2 -> BlueNoise.halftone(gray, W, H, 238, 40)
            else -> BlueNoise.plain(gray, threshold)
        }
        if (onDebug != null) onDebug(physicalPreview(whiteMask))
        val payload = if (mode == 1) pack4(gray) else pack1FromMask(whiteMask)
        return packet(payload, mode, seq, flags)
    }

    /** 論理 528x792 (gray[y*528+x]) -> 物理 792x528 (p[py*792+px]), px=y, py=527-x */
    private fun toPhysicalGray(logical: IntArray): IntArray {
        val out = IntArray(W * H)
        for (py in 0 until H) {
            // 論理 x = (H-1)-py, 論理 y = px。P(py,px) = L(y=px, x=527-py) を数値検証済み
            val x = (H - 1) - py
            val rowBase = py * W
            for (px in 0 until W) {
                val argb = logical[px * LW + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                out[rowBase + px] = (r * 299 + g * 587 + b * 114) / 1000   // 輝度 0..255
            }
        }
        return out
    }

    private fun pack1FromMask(white: BooleanArray): ByteArray {
        val stride = (W + 7) / 8
        val out = ByteArray(stride * H)
        for (y in 0 until H) {
            val base = y * stride
            for (x in 0 until W) {
                if (!white[y * W + x]) continue
                out[base + (x shr 3)] = (out[base + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
        return out
    }

    private fun spread(g: IntArray, x: Int, y: Int, err: Int) {
        fun add(xx: Int, yy: Int, f: Int) {
            if (xx in 0 until W && yy in 0 until H) {
                val i = yy * W + xx
                g[i] = (g[i] + err * f / 16).coerceIn(0, 255)
            }
        }
        add(x + 1, y, 7); add(x - 1, y + 1, 3); add(x, y + 1, 5); add(x + 1, y + 1, 1)
    }

    /** 2bpp, row-major, 4px/byte。0=白 1=濃灰 2=薄灰 3=黒 */
    private fun pack4(gray: IntArray): ByteArray {
        val out = ByteArray((W * H + 3) / 4)
        var bitPos = 0
        for (i in gray.indices) {
            val v = gray[i]
            val lvl = when {
                v >= 192 -> 0
                v >= 128 -> 2
                v >= 64 -> 1
                else -> 3
            }
            val shift = 6 - 2 * (bitPos and 3)
            out[bitPos shr 2] = (out[bitPos shr 2].toInt() or (lvl shl shift)).toByte()
            bitPos++
        }
        return out
    }

    /** デバッグ用: 実際にX3へ送る2値画像そのもの */
    private fun physicalPreview(whiteMask: BooleanArray): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val px = IntArray(W * H)
        for (i in whiteMask.indices) px[i] = if (whiteMask[i]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        bmp.setPixels(px, 0, W, 0, 0, W, H)
        return bmp
    }

    private fun packet(payload: ByteArray, mode: Int, seq: Int, flags: Int): ByteArray {
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val head = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        head.put(MAGIC.toByteArray(Charsets.US_ASCII))
        head.put(VERSION.toByte())
        head.put(mode.toByte())
        head.putShort(W.toShort())
        head.putShort(H.toShort())
        head.putInt((seq and 0x00FFFFFF) or ((flags and 0xFF) shl 24))
        head.putInt(payload.size)
        head.putInt(crc)
        return head.array() + payload
    }
}
