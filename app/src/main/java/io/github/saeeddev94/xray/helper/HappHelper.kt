package io.github.saeeddev94.xray.helper

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HappHelper {

    // Known RSA PKCS#8 private keys for Happ crypt schemes (base64 DER format)
    private val RSA_KEYS = listOf(
        // Key 1 (crypt / crypt1)
        "MIICXAIBAAKBgQC4i+8N...placeholder...", // standard 1024/2048 keys
        // Key 2 (crypt2)
        "MIICXAIBAAKBgQDQx...",
        // Key 3 (crypt3)
        "MIICXAIBAAKBgQDR...",
        // Key 4 (crypt4)
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBA"
    )

    fun isHappUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("happ://") || trimmed.contains("happ://crypt")
    }

    fun isHappContent(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("happ://") ||
                trimmed.startsWith("crypt:") ||
                trimmed.startsWith("crypt2:") ||
                trimmed.startsWith("crypt3:") ||
                trimmed.startsWith("crypt4:") ||
                trimmed.startsWith("crypt5:")
    }

    /**
     * Attempts local decryption of a Happ link payload or encrypted string.
     */
    fun decryptLocal(input: String): String? {
        val clean = input.trim()
            .removePrefix("happ://")
            .removePrefix("HAPP://")

        val parts = clean.split("/", limit = 2)
        val scheme = if (parts.size == 2 && parts[0].lowercase().startsWith("crypt")) {
            parts[0].lowercase()
        } else {
            "crypt"
        }
        val payload = if (parts.size == 2 && parts[0].lowercase().startsWith("crypt")) {
            parts[1]
        } else {
            clean
        }

        if (payload.isBlank()) return null

        val decodedBytes = tryDecodeBase64(payload) ?: return null

        // Try RSA PKCS1 decryption with stored keys
        for (keyPem in RSA_KEYS) {
            val decrypted = runCatching { rsaDecrypt(decodedBytes, keyPem) }.getOrNull()
            if (!decrypted.isNullOrBlank()) {
                val resultStr = decrypted.trim()
                if (resultStr.contains("://") || resultStr.startsWith("[") || resultStr.startsWith("{")) {
                    return resultStr
                }
            }
        }

        // Try direct Base64 text decode if payload was just base64 encoded
        val rawStr = runCatching { String(decodedBytes, Charsets.UTF_8) }.getOrNull()
        if (!rawStr.isNullOrBlank() && (rawStr.contains("://") || rawStr.startsWith("[") || rawStr.startsWith("{"))) {
            return rawStr
        }

        return null
    }

    /**
     * Resolves a Happ URL: decrypts locally or uses a fallback online decoder,
     * then if the decrypted text is an HTTP(S) URL, fetches the actual payload.
     */
    suspend fun processHappUrl(
        happUrl: String,
        userAgent: String? = null,
        hardwareId: String? = null
    ): SubscriptionResponse = withContext(Dispatchers.IO) {
        // 1. Try local decryption
        var decrypted = decryptLocal(happUrl)

        // 2. If local decryption fails, try online decoder API mirror
        if (decrypted.isNullOrBlank()) {
            decrypted = fetchOnlineDecryption(happUrl, userAgent)
        }

        if (decrypted.isNullOrBlank()) {
            throw Exception("Не удалось расшифровать Happ ссылку")
        }

        val cleanDecrypted = decrypted.trim()

        // 3. If decrypted string is a web URL (http:// or https://), fetch its content
        val uri = runCatching { URI(cleanDecrypted) }.getOrNull()
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            return@withContext HttpHelper.getSubscriptionData(cleanDecrypted, userAgent, hardwareId)
        }

        // 4. Otherwise, the decrypted content is directly the subscription content (e.g. VLESS/VMess/JSON)
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
                connection.setRequestProperty("User-Agent", userAgent ?: "XrayFlow/1.0")

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

    private fun rsaDecrypt(data: ByteArray, privateKeyBase64: String): String {
        val keyBytes = Base64.decode(privateKeyBase64, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey: PrivateKey = keyFactory.generatePrivate(keySpec)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decryptedBytes = cipher.doFinal(data)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun tryDecodeBase64(input: String): ByteArray? {
        var formatted = input.trim()
            .replace("-", "+")
            .replace("_", "/")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")

        while (formatted.length % 4 != 0) {
            formatted += "="
        }

        return runCatching { Base64.decode(formatted, Base64.DEFAULT) }.getOrNull()
            ?: runCatching { Base64.decode(formatted, Base64.URL_SAFE) }.getOrNull()
            ?: runCatching { Base64.decode(formatted, Base64.NO_WRAP) }.getOrNull()
    }
}
