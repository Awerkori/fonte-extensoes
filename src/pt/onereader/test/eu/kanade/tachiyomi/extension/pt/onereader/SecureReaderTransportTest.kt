package eu.kanade.tachiyomi.extension.pt.onereader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureReaderTransportTest {
    @Test
    fun restoresChapterIdentityWhenHostDropsExtensionMemo() {
        assertEquals("o-culto-do-dinheiro" to "52", resolveChapterIdentity("o-culto-do-dinheiro/52", null, null))
    }

    @Test
    fun unwrapsTheServerKeyUsingEphemeralEcdh() {
        val transport = SecureReaderTransport.create()
        val serverPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE_NAME))
        }.generateKeyPair()
        val clientPublic = rawToPublic(decode(transport.clientKey))
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(serverPair.private)
            doPhase(clientPublic, true)
            generateSecret()
        }

        val context = "reader-test-context"
        val prefix = "oneReader-keywrap-ecdh-p256-v1\n".toByteArray()
        val wrappingKey = java.security.MessageDigest.getInstance("SHA-256").digest(
            prefix + sharedSecret + "\n$context".toByteArray(),
        )
        val iv = ByteArray(12) { it.toByte() }
        val targetKey = ByteArray(32) { (it * 3).toByte() }
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, iv))
            updateAAD(prefix + context.toByteArray())
            doFinal(targetKey)
        }

        val keyWrap = MediaKeyWrapDto(
            mode = "ecdh-p256-aesgcm-v1",
            serverKey = encode(pointToRaw(serverPair.public as ECPublicKey)),
            iv = encode(iv),
            payload = encode(encrypted),
            context = context,
        )

        assertArrayEquals(targetKey, transport.unwrap(keyWrap))
    }

    @Test
    fun decryptsOrx4ImagePayload() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 20).toByte() }
        val image = "RIFF".toByteArray() + byteArrayOf(4, 3, 2, 1) + "WEBP".toByteArray()
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD("oneReader-orx4-v1".toByteArray())
            doFinal(image)
        }

        assertArrayEquals(image, Orx4Decoder.decode("ORX4".toByteArray() + encrypted, key, encode(nonce)))
    }

    private fun rawToPublic(raw: ByteArray): ECPublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(CURVE_NAME))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))),
                parameters,
            ),
        ) as ECPublicKey
    }

    private fun pointToRaw(publicKey: ECPublicKey): ByteArray = byteArrayOf(0x04) +
        publicKey.w.affineX.toFixedBytes() + publicKey.w.affineY.toFixedBytes()

    private fun BigInteger.toFixedBytes(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)

    private companion object {
        const val CURVE_NAME = "secp256r1"
    }
}
