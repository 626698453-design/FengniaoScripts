package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.preferences.HchatStorage
import java.net.URI

/** Download service only; the standalone module does not contain the plugin market. */
object DistributionSettings {
    const val DEFAULT_SERVICE_URL = "https://hchat.103.97.179.142.sslip.io"
    fun serviceUrl(context: Context): String = HchatStorage.preferences(context, "Fengniao_distribution")
        .getString("base_url", DEFAULT_SERVICE_URL).orEmpty().trim().trimEnd('/')
    fun saveServiceUrl(context: Context, value: String) {
        val url = value.trim().trimEnd('/')
        val parsed = URI(url)
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null) { "下载源需要有效的 HTTPS 地址" }
        HchatStorage.preferences(context, "Fengniao_distribution").edit().putString("base_url", url).apply()
    }
}
