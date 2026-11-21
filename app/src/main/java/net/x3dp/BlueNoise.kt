package net.x3dp

import java.util.Base64

/**
 * 64x64 ブルーノイズ閾値テーブル(0..254)。
 * CrossPoint の "Fast" グレースケール描画と同じ考え方で、8bitグレーを
 * 単一のBWパスで滑らかに見せる。
 *
 * 重要: テーブル最大値は 254。255 を含めると「白(255) > 254」が偽になり
 * 白地に規則的な黒点が乗る(実測でハマった)。
 * また中間調の外側(whitePoint以上/blackPoint以下)はディザせず即決めする。
 * そうしないと文字のエッジに白い粒が乗る。
 */
object BlueNoise {
    private val B64: String =
        "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" +
        "2qMZ5weuGFa2RGKQB69fhknagFKpcAhPIA=="

    private val table: ByteArray by lazy { Base64.getDecoder().decode(B64) }

    fun threshold(gray: IntArray, width: Int, height: Int, whitePoint: Int, blackPoint: Int): BooleanArray {
        val out = BooleanArray(gray.size)
        val span = (whitePoint - blackPoint).coerceAtLeast(1)
        for (y in 0 until height) {
            val base = (y and 63) * 64
            for (x in 0 until width) {
                val i = y * width + x
                val raw = gray[i]
                when {
                    raw >= whitePoint -> out[i] = true
                    raw <= blackPoint -> out[i] = false
                    else -> {
                        val v = (raw - blackPoint) * 255 / span
                        val t = table[base + (x and 63)].toInt() and 0xFF
                        out[i] = v > t
                    }
                }
            }
        }
        return out
    }

    /** 8x8 Bayer(ハーフトーン寄り)。値は 0..252。 */
    private val BAYER8: IntArray = intArrayOf(0, 128, 32, 160, 8, 136, 40, 168, 192, 64, 224, 96, 200, 72, 232, 104, 48, 176, 16, 144, 56, 184, 24, 152, 240, 112, 208, 80, 248, 120, 216, 88, 12, 140, 44, 172, 4, 132, 36, 164, 204, 76, 236, 108, 196, 68, 228, 100, 60, 188, 28, 156, 52, 180, 20, 148, 252, 124, 220, 92, 244, 116, 212, 84)

    /** 4段階に量子化してから Bayer で 2値化。粒が大きめでノイズ感が少ない。 */
    fun halftone(gray: IntArray, width: Int, height: Int, whitePoint: Int, blackPoint: Int): BooleanArray {
        val out = BooleanArray(gray.size)
        val span = (whitePoint - blackPoint).coerceAtLeast(1)
        for (y in 0 until height) {
            val base = (y and 7) * 8
            for (x in 0 until width) {
                val i = y * width + x
                val raw = gray[i]
                when {
                    raw >= whitePoint -> out[i] = true
                    raw <= blackPoint -> out[i] = false
                    else -> {
                        var v = (raw - blackPoint) * 255 / span
                        // 4段階に丸める(0/85/170/255)
                        v = ((v + 42) / 85) * 85
                        out[i] = v > BAYER8[base + (x and 7)]
                    }
                }
            }
        }
        return out
    }

    fun plain(gray: IntArray, threshold: Int): BooleanArray =
        BooleanArray(gray.size) { gray[it] >= threshold }
}
