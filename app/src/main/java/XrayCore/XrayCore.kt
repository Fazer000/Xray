package XrayCore

import android.util.Base64

object XrayCore {
    @JvmStatic
    fun version(): String = "24.12.31"

    @JvmStatic
    fun test(dir: String, config: String): String = ""

    @JvmStatic
    fun start(dir: String, config: String): String = ""

    @JvmStatic
    fun stop(): String = ""

    @JvmStatic
    fun json(link: String): String {
        val json = """{"success": true, "data": {"outbounds": [{"protocol": "vless"}]}}"""
        return Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
    }
}
