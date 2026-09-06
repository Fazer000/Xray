package io.github.saeeddev94.xray.helper

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HappHelper {

    private var nativeKeys: List<PrivateKey>? = null
    private var crypt5Keys: Map<String, String>? = null

    private fun ensureSecurityProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Synchronized
    fun initKeys(context: Context) {
        if (nativeKeys != null && crypt5Keys != null) return
        ensureSecurityProvider()
        runCatching {
            val nativeJsonStr = context.assets.open("native_keys.json").bufferedReader().use { it.readText() }
            val nativeObj = JSONObject(nativeJsonStr)
            val keysArr = nativeObj.getJSONArray("keys")
            val parsedNative = mutableListOf<PrivateKey>()
            for (i in 0 until keysArr.length()) {
                parsedNative.add(parsePrivateKey(keysArr.getString(i)))
            }
            nativeKeys = parsedNative

            val crypt5JsonStr = context.assets.open("crypt5_final_keys.json").bufferedReader().use { it.readText() }
            val crypt5Obj = JSONObject(crypt5JsonStr)
            val crypt5KeysObj = crypt5Obj.getJSONObject("keys")
            val parsedCrypt5 = mutableMapOf<String, String>()
            val keysIter = crypt5KeysObj.keys()
            while (keysIter.hasNext()) {
                val k = keysIter.next()
                parsedCrypt5[k] = crypt5KeysObj.getString(k)
            }
            crypt5Keys = parsedCrypt5
        }.onFailure {
            it.printStackTrace()
        }
    }

    fun isHappUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("happ://") || trimmed.contains("happ://crypt")
    }

    fun isHappContent(content: String): Boolean {
        val trimmed = content.trim().lowercase()
        return trimmed.startsWith("happ://") ||
                trimmed.startsWith("crypt:") ||
                trimmed.startsWith("crypt2:") ||
                trimmed.startsWith("crypt3:") ||
                trimmed.startsWith("crypt4:") ||
                trimmed.startsWith("crypt5:")
    }

    fun shuffleBlocks(text: String, blockSize: Int, order: IntArray): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val full = (bytes.size / blockSize) * blockSize
        val out = ByteArray(bytes.size)
        var outIdx = 0
        var i = 0
        while (i < full) {
            for (o in order) {
                out[outIdx++] = bytes[i + o]
            }
            i += blockSize
        }
        while (i < bytes.size) {
            out[outIdx++] = bytes[i++]
        }
        return String(out, Charsets.UTF_8)
    }

    fun m4831f(text: String): String = shuffleBlocks(text, 6, intArrayOf(1, 3, 5, 0, 2, 4))
    fun inverseM4831f(text: String): String = shuffleBlocks(text, 6, intArrayOf(3, 0, 4, 1, 5, 2))
    fun m4842j(text: String): String = shuffleBlocks(text, 2, intArrayOf(1, 0))
    fun permute4(text: String): String = shuffleBlocks(text, 4, intArrayOf(2, 3, 0, 1))

    fun b64Decode(text: String): ByteArray {
        val clean = text.trim()
        val variants = listOf(clean, clean.trimEnd('='))
        val flagsList = listOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP)
        for (v in variants) {
            val padLen = (4 - v.length % 4) % 4
            val padded = v + "=".repeat(padLen)
            for (flags in flagsList) {
                val decoded = runCatching { Base64.decode(padded, flags) }.getOrNull()
                if (decoded != null && decoded.isNotEmpty()) {
                    return decoded
                }
            }
        }
        throw IllegalArgumentException("Invalid base64 string")
    }

    fun rsaDecrypt(key: PrivateKey, ciphertextBytes: ByteArray): String {
        ensureSecurityProvider()
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", "BC")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val decryptedBytes = cipher.doFinal(ciphertextBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun parsePrivateKey(base64Der: String): PrivateKey {
        ensureSecurityProvider()
        val keyBytes = Base64.decode(base64Der, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA", "BC")
        return keyFactory.generatePrivate(keySpec)
    }

    fun decryptChaCha20Poly1305(keyBytes: ByteArray, nonceBytes: ByteArray, cipherBytes: ByteArray): ByteArray {
        ensureSecurityProvider()
        val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
        val keySpec = SecretKeySpec(keyBytes, "ChaCha20")
        val ivSpec = IvParameterSpec(nonceBytes)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(cipherBytes)
    }

    private fun decryptCrypt5(payload: String, crypt5Map: Map<String, String>): String {
        val original = inverseM4831f(payload)
        val shuffled = permute4(original)
        if (shuffled.length < 8) throw IllegalArgumentException("crypt5 payload too short")

        val marker = shuffled.take(4) + shuffled.takeLast(4)
        val body = shuffled.substring(4, shuffled.length - 4)
        if (body.length < 13) throw IllegalArgumentException("crypt5 body too short")

        val nonce = body.substring(0, 12).toByteArray(Charsets.UTF_8)
        val rest = body.substring(12)

        var digitCount = 0
        while (digitCount < rest.length && rest[digitCount].isDigit()) {
            digitCount++
        }
        if (digitCount == 0) throw IllegalArgumentException("crypt5 segment length missing")

        val segmentLen = rest.substring(0, digitCount).toInt()
        val packed = rest.substring(digitCount)

        if (packed.length < 1 + segmentLen) throw IllegalArgumentException("crypt5 encrypted segment truncated")

        val encryptedSegment = packed.substring(1, 1 + segmentLen)
        val rsaCiphertext = packed.substring(1 + segmentLen)

        val keyPem = crypt5Map[marker] ?: throw IllegalArgumentException("unknown crypt5 marker: $marker")
        val privateKey = parsePrivateKey(keyPem)
        val rsaPlain = rsaDecrypt(privateKey, b64Decode(rsaCiphertext))

        val chachaKeyB64 = m4842j(rsaPlain)
        val chachaKey = b64Decode(chachaKeyB64)
        if (chachaKey.size != 32) throw IllegalArgumentException("ChaCha20 key has invalid length: ${chachaKey.size}")

        val encryptedBytes = b64Decode(encryptedSegment)
        val decryptedBytes = decryptChaCha20Poly1305(chachaKey, nonce, encryptedBytes)

        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun decrypt(value: String, context: Context? = null): String {
        ensureSecurityProvider()
        if (context != null) initKeys(context)

        val cleanVal = value.trim()
        val prefixes = listOf(
            Pair("happ://crypt5/", 4),
            Pair("happ://crypt4/", 3),
            Pair("happ://crypt3/", 2),
            Pair("happ://crypt2/", 1),
            Pair("happ://crypt/", 0)
        )

        var mode = 4
        var payload = cleanVal
        for ((prefix, m) in prefixes) {
            if (cleanVal.startsWith(prefix, ignoreCase = true)) {
                mode = m
                payload = cleanVal.substring(prefix.length)
                break
            }
        }

        val keys = nativeKeys ?: throw IllegalStateException("Happ native keys not loaded")
        val c5Keys = crypt5Keys ?: throw IllegalStateException("Happ crypt5 keys not loaded")

        return if (mode == 4) {
            val step1 = m4831f(payload)
            val step2 = decryptCrypt5(step1, c5Keys)
            val step3 = m4842j(step2)
            val finalBytes = b64Decode(step3)
            String(finalBytes, Charsets.UTF_8)
        } else {
            val key = keys.getOrNull(mode) ?: throw IllegalArgumentException("Native key mode $mode not available")
            rsaDecrypt(key, b64Decode(payload))
        }
    }

    fun decryptLocal(input: String, context: Context? = null): String? {
        return runCatching { decrypt(input, context) }.getOrNull()
    }

    suspend fun processHappUrl(
        happUrl: String,
        context: Context? = null,
        userAgent: String? = null,
        hardwareId: String? = null
    ): SubscriptionResponse = withContext(Dispatchers.IO) {
        var decrypted = decryptLocal(happUrl, context)

        if (decrypted.isNullOrBlank()) {
            decrypted = fetchOnlineDecryption(happUrl, userAgent)
        }

        if (decrypted.isNullOrBlank()) {
            throw Exception("Не удалось расшифровать Happ ссылку")
        }

        val cleanDecrypted = decrypted.trim()

        val uri = runCatching { URI(cleanDecrypted) }.getOrNull()
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            val res = HttpHelper.getSubscriptionData(cleanDecrypted, userAgent ?: "Happ/3.13.0", hardwareId, context)
            val bodyTrimmed = res.body.trim()
            if (isHappContent(bodyTrimmed)) {
                val bodyDecrypted = decryptLocal(bodyTrimmed, context)
                if (!bodyDecrypted.isNullOrBlank()) {
                    return@withContext res.copy(body = bodyDecrypted)
                }
            }
            return@withContext res
        }

        SubscriptionResponse(body = cleanDecrypted)
    }

    private fun fetchOnlineDecryption(happUrl: String, userAgent: String?): String? {
        val endpoints = listOf(
            "https://happy-decoder.cc/api/decrypt?url=",
            "https://crypto.happ.su/decrypt.php?url="
        )

        for (endpoint in endpoints) {
            runCatching {
                val encodedUrl = URLEncoder.encode(happUrl, "UTF-8")
                val connection = URL(endpoint + encodedUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.setRequestProperty("User-Agent", userAgent ?: "Happ/3.13.0")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (text.isNotEmpty() && !text.contains("error", ignoreCase = true)) {
                        return text
                    }
                }
            }
        }
        return null
    }
}
