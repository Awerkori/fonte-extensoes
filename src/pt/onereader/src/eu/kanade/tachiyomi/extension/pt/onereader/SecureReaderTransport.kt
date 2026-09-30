package eu.kanade.tachiyomi.extension.pt.onereader

import okhttp3.HttpUrl
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Implements the ephemeral P-256 transport used by the site's ScanReader. */
internal class SecureReaderTransport internal constructor(private val keyPair: KeyPair) {
    val clientKey: String = pointToRaw(keyPair.public as ECPublicKey).encodeBase64Url()
    private var proofKey: ByteArray? = null
    private var configuredServerKey: String? = null

    fun configure(descriptor: TransportDescriptorDto?) {
        if (descriptor == null) return
        val serverKey = descriptor.serverKey
        if (descriptor.mode != KEY_WRAP_MODE || descriptor.requestProof != PROOF_MODE || serverKey.isBlank()) {
            return
        }
        if (configuredServerKey == serverKey && proofKey != null) return

        val serverPublicKey = rawToPoint(serverKey.decodeBase64Url())
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(serverPublicKey, true)
            generateSecret()
        }
        val prefix = "oneReader-scan-proof-hmac-v1\n".toByteArray(Charsets.UTF_8)
        val material = prefix + sharedSecret
        proofKey = MessageDigest.getInstance("SHA-256").digest(material)
        configuredServerKey = serverKey
    }

    fun buildProofHeaders(pageUrl: HttpUrl): Map<String, String> {
        val key = proofKey ?: return emptyMap()
        val timestamp = System.currentTimeMillis().toString()
        val nonceBytes = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val nonce = nonceBytes.encodeBase64Url()
        val grant = pageUrl.queryParameter("g").orEmpty()
        val canonical = listOf(
            "oneReader-scan-proof-v1",
            "GET",
            pageUrl.encodedPath,
            grant,
            timestamp,
            nonce,
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        val proof = mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).encodeBase64Url()
        return mapOf(
            "X-OneReader-Proof-Ts" to timestamp,
            "X-OneReader-Proof-Nonce" to nonce,
            "X-OneReader-Proof" to proof,
        )
    }

    fun unwrap(keyWrap: MediaKeyWrapDto): ByteArray {
        if (keyWrap.mode != KEY_WRAP_MODE) throw IOException("Envelope de chave do ScanReader inválido")

        val serverKey = rawToPoint(keyWrap.serverKey.decodeBase64Url())
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(serverKey, true)
            generateSecret()
        }

        val contextBytes = keyWrap.context.toByteArray(Charsets.UTF_8)
        val material = PREFIX + sharedSecret + byteArrayOf('\n'.code.toByte()) + contextBytes
        val wrappingKey = MessageDigest.getInstance("SHA-256").digest(material)
        val wrapped = keyWrap.payload.decodeBase64Url()
        if (wrapped.size <= GCM_TAG_SIZE) throw IOException("Envelope de chave do ScanReader truncado")

        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, keyWrap.iv.decodeBase64Url()))
                updateAAD(PREFIX + contextBytes)
                doFinal(wrapped)
            }
        } catch (e: Exception) {
            throw IOException("Falha ao abrir a chave efêmera do ScanReader", e)
        }
    }

    private fun rawToPoint(raw: ByteArray): ECPublicKey {
        if (raw.size != 65 || raw[0] != 0x04.toByte()) throw IOException("Chave pública P-256 inválida")
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(CURVE_NAME))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters)) as ECPublicKey
    }

    private fun pointToRaw(publicKey: ECPublicKey): ByteArray = byteArrayOf(0x04) +
        publicKey.w.affineX.toFixedBytes() + publicKey.w.affineY.toFixedBytes()

    private fun BigInteger.toFixedBytes(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == COORDINATE_SIZE -> bytes
            bytes.size > COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - COORDINATE_SIZE, bytes.size)
            else -> ByteArray(COORDINATE_SIZE - bytes.size) + bytes
        }
    }

    companion object {
        private const val CURVE_NAME = "secp256r1"
        private const val COORDINATE_SIZE = 32
        private const val GCM_TAG_SIZE = 16
        private const val KEY_WRAP_MODE = "ecdh-p256-aesgcm-v1"
        private const val PROOF_MODE = "hmac-sha256-v1"
        private val PREFIX = "oneReader-keywrap-ecdh-p256-v1\n".toByteArray(Charsets.UTF_8)
        private val secureRandom = SecureRandom()

        fun create(): SecureReaderTransport {
            val pair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec(CURVE_NAME))
            }.generateKeyPair()
            return SecureReaderTransport(pair)
        }
    }
}

internal object Orx4Decoder {
    fun decode(encrypted: ByteArray, key: ByteArray, nonce: String): ByteArray {
        if (encrypted.size < 24 || !encrypted.copyOfRange(0, 4).contentEquals(ORX4_MAGIC)) {
            throw IOException("Envelope ORX4 inválido")
        }
        val iv = nonce.decodeBase64Url()
        if (key.size != 32 || iv.size != 12) throw IOException("Chave/nonce ORX4 inválidos")

        val clear = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(ORX4_AAD)
                doFinal(encrypted, 4, encrypted.size - 4)
            }
        } catch (e: Exception) {
            throw IOException("Falha de autenticação do payload ORX4", e)
        }

        if (!hasImageSignature(clear)) throw IOException("A mídia ORX4 não contém uma imagem válida")
        return clear
    }

    private fun hasImageSignature(bytes: ByteArray): Boolean = (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(RIFF) && bytes.copyOfRange(8, 12).contentEquals(WEBP)) ||
        (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG)) ||
        (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())

    private val ORX4_MAGIC = byteArrayOf(0x4f, 0x52, 0x58, 0x34)
    private val ORX4_AAD = "oneReader-orx4-v1".toByteArray(Charsets.UTF_8)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}

internal fun String.decodeBase64Url(): ByteArray = try {
    Base64.getUrlDecoder().decode(this)
} catch (e: IllegalArgumentException) {
    throw IOException("Valor Base64URL inválido", e)
}

internal fun ByteArray.encodeBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
