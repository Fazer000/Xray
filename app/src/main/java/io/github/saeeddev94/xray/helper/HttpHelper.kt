package io.github.saeeddev94.xray.helper

import android.os.Build
import io.github.saeeddev94.xray.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

data class SubscriptionResponse(
    val body: String,
    val userInfo: String? = null,
    val profileTitle: String? = null,
    val profileWebPageUrl: String? = null,
    val announcement: String? = null,
)

class HttpHelper(
    val scope: CoroutineScope,
    val settings: Settings,
) {

    companion object {
        private fun getConnection(
            link: String,
            method: String = "GET",
            proxy: Proxy? = null,
            timeout: Int = 5000,
            userAgent: String? = null,
            hardwareId: String? = null,
        ): HttpURLConnection {
            val url = URL(link)
            val connection = if (proxy == null) {
                url.openConnection() as HttpURLConnection
            } else {
                url.openConnection(proxy) as HttpURLConnection
            }
            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = timeout

            val ua = if (!userAgent.isNullOrBlank()) userAgent else "XrayFlow"
            val hwid = if (!hardwareId.isNullOrBlank()) hardwareId else "74jf74nf8f4jr5je"
            val locale = runCatching { Locale.getDefault().language.ifBlank { "ru" } }.getOrDefault("ru")
            val model = Build.MODEL ?: ""
            val verOs = Build.VERSION.RELEASE ?: ""

            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("X-Device-Os", "Android")
            connection.setRequestProperty("X-Device-Locale", locale)
            connection.setRequestProperty("X-Device-Model", model)
            connection.setRequestProperty("X-Ver-Os", verOs)
            connection.setRequestProperty("X-Hwid", hwid)
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Connection", "keep-alive")
            return connection
        }

        suspend fun getSubscriptionData(
            link: String,
            userAgent: String? = null,
            hardwareId: String? = null,
        ): SubscriptionResponse {
            if (HappHelper.isHappUrl(link)) {
                return HappHelper.processHappUrl(link, userAgent, hardwareId)
            }
            return withContext(Dispatchers.IO) {
                val connection = getConnection(
                    link,
                    userAgent = userAgent,
                    hardwareId = hardwareId,
                )
                var responseCode = 0
                var userInfo: String? = null
                var profileTitle: String? = null
                var profileWebPageUrl: String? = null
                var announcement: String? = null

                var responseBody = try {
                    connection.connect()
                    responseCode = connection.responseCode
                    userInfo = connection.getHeaderField("subscription-userinfo")
                        ?: connection.getHeaderField("Subscription-Userinfo")
                        ?: connection.getHeaderField("user-info")
                    profileTitle = connection.getHeaderField("profile-title")
                        ?: connection.getHeaderField("Profile-Title")
                    profileWebPageUrl = connection.getHeaderField("profile-web-page-url")
                        ?: connection.getHeaderField("Profile-Web-Page-Url")
                    announcement = connection.getHeaderField("announcement")
                        ?: connection.getHeaderField("profile-update-interval")

                    val rawStream = connection.inputStream
                    val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
                    val stream = if (isGzip) GZIPInputStream(rawStream) else rawStream
                    stream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    null
                } finally {
                    connection.disconnect()
                }
                if (responseCode != HttpURLConnection.HTTP_OK || responseBody == null) {
                    throw Exception("HTTP Error: $responseCode")
                }

                if (HappHelper.isHappContent(responseBody)) {
                    val decrypted = HappHelper.decryptLocal(responseBody)
                    if (!decrypted.isNullOrBlank()) {
                        responseBody = decrypted
                    }
                }

                SubscriptionResponse(
                    body = responseBody,
                    userInfo = userInfo,
                    profileTitle = profileTitle,
                    profileWebPageUrl = profileWebPageUrl,
                    announcement = announcement,
                )
            }
        }

        suspend fun get(link: String, userAgent: String? = null, hardwareId: String? = null): String {
            return getSubscriptionData(link, userAgent, hardwareId).body
        }
    }

    fun measureDelay(proxy: Boolean, callback: (result: String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val connection = getConnection(proxy)
            var result = "HTTP {status}, {delay} ms"

            result = try {
                setSocksAuth(getSocksAuth())
                val responseCode = connection.responseCode
                result.replace("{status}", "$responseCode")
            } catch (error: Exception) {
                error.message ?: "Http delay measure failed"
            } finally {
                connection.disconnect()
                setSocksAuth(null)
            }

            val delay = System.currentTimeMillis() - start
            withContext(Dispatchers.Main) {
                callback(result.replace("{delay}", "$delay"))
            }
        }
    }

    private suspend fun getConnection(withProxy: Boolean): HttpURLConnection {
        return withContext(Dispatchers.IO) {
            val link = settings.pingAddress
            val method = "HEAD"
            val address = InetSocketAddress(settings.socksAddress, settings.socksPort.toInt())
            val proxy = if (withProxy) Proxy(Proxy.Type.SOCKS, address) else null
            val timeout = settings.pingTimeout * 1000

            getConnection(link, method, proxy, timeout)
        }
    }

    private fun getSocksAuth(): Authenticator? {
        if (
            settings.socksUsername.trim().isEmpty() || settings.socksPassword.trim().isEmpty()
        ) return null
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(
                    settings.socksUsername,
                    settings.socksPassword.toCharArray()
                )
            }
        }
    }

    private fun setSocksAuth(auth: Authenticator?) {
        Authenticator.setDefault(auth)
    }

}
