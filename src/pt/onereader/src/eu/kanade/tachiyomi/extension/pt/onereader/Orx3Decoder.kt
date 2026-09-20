package eu.kanade.tachiyomi.extension.pt.onereader

import android.util.Base64
import java.io.IOException

internal object Orx3Decoder {
    fun decode(encrypted: ByteArray, keyValue: String): ByteArray {
        if (encrypted.size < 20 || !encrypted.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4f, 0x52, 0x58, 0x33))) {
            throw IOException("Envelope ORX3 inválido")
        }
        val raw = keyValue.replace('-', '+').replace('_', '/')
            .padEnd(((keyValue.length + 3) / 4) * 4, '=')
        val key = Base64.decode(raw, Base64.DEFAULT)
        if (key.isEmpty()) throw IOException("Chave ORX3 ausente")
        val count = (
            ((encrypted[4].toInt() and 0xff) shl 24) or
                ((encrypted[5].toInt() and 0xff) shl 16) or
                ((encrypted[6].toInt() and 0xff) shl 8) or
                (encrypted[7].toInt() and 0xff)
            ).coerceIn(0, encrypted.size - 8)
        val output = encrypted.copyOfRange(8, encrypted.size)
        for (i in 0 until count) output[i] = (output[i].toInt() xor (key[i % key.size].toInt() and 0xff)).toByte()
        return output
    }
}
