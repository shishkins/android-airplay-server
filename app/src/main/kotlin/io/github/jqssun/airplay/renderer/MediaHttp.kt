package io.github.jqssun.airplay.renderer

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress

/** Networking rules for website video the receiver fetches on the sender's behalf. */
internal object MediaHttp {

    /**
     * IPv4 first. Sites sign media URLs for the address the sender's browser came from. At home
     * every device shares one public IPv4 (NAT), but each has its own IPv6 address: a URL signed
     * for the phone's IPv4 is refused when the receiver reaches a dual-stack CDN over IPv6
     * (seen as HTTP 403/412). IPv6 stays as the fallback for IPv6-only hosts.
     */
    object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    /**
     * Cookies within one playback, as AVPlayer keeps them: token-protected CDNs (Akamai hdnea →
     * hdntl) answer the master playlist with a session cookie that every segment request must carry.
     * Cleared on each new /play; never persisted.
     */
    class SessionCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                this.cookies += c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }

        @Synchronized
        fun clear() = cookies.clear()
    }

    /**
     * Hotlink-protected CDNs serve playlists to anyone but media segments only with the site's
     * page as Referer (HTTP 404 without it, 200 with it). AirPlay does not tell the receiver which
     * page the video came from, so the site is inferred from the CDN host. The rules live on the
     * device, not in the source: `persist.z7.referer` = "cdn.example=https://site.example/;...".
     */
    object SiteReferer : Interceptor {
        private const val PROP = "persist.z7.referer"

        @Volatile private var cached: Pair<String, List<Pair<String, String>>>? = null

        private fun rules(): List<Pair<String, String>> {
            val raw = systemProperty(PROP)
            cached?.let { if (it.first == raw) return it.second }
            val parsed = raw.split(';').mapNotNull { rule ->
                val i = rule.indexOf('=')
                if (i <= 0) null else rule.substring(0, i).trim().trimStart('.').lowercase() to rule.substring(i + 1).trim()
            }.filter { it.first.isNotEmpty() && it.second.startsWith("http") }
            cached = raw to parsed
            return parsed
        }

        fun refererFor(host: String): String? {
            val h = host.lowercase()
            return rules().firstOrNull { (suffix, _) -> h == suffix || h.endsWith(".$suffix") }?.second
        }

        @android.annotation.SuppressLint("PrivateApi")
        private fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String
        } catch (_: Exception) {
            ""
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header("Referer") != null) return chain.proceed(request)
            val referer = refererFor(request.url.host) ?: return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("Referer", referer).build())
        }
    }

    fun isLocal(host: String?): Boolean =
        host == null || host == "localhost" || host == "127.0.0.1" || host == "::1"
}
