package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class LiveScraperTest {
    @Test
    fun fetchRegasWithProxy() = runBlocking {
        val proxyListStr = Request.Builder().url("https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=10000&country=BR&ssl=all&anonymity=all").build().let {
            OkHttpClient().newCall(it).execute().body?.string() ?: ""
        }
        val proxies = proxyListStr.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        println("Found \${proxies.size} proxies")

        val htmls = java.util.concurrent.ConcurrentHashMap<String, String>()

        val jobs = proxies.take(50).map { proxyStr ->
            async(Dispatchers.IO) {
                try {
                    val (ip, port) = proxyStr.split(":")
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(ip, port.toInt()))
                    val client = OkHttpClient.Builder()
                        .proxy(proxy)
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("https://3xyaoi.com/manga/regas/capitulo-67/")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .addHeader("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                        .addHeader("Referer", "https://3xyaoi.com/manga/regas/")
                        .build()
                    val response = client.newCall(request).execute()
                    val html = response.body?.string() ?: ""
                    if (!html.contains("Attention Required") && !html.contains("Just a moment") && !html.contains("Acesso bloqueado")) {
                        htmls[proxyStr] = html
                        println("SUCCESS with proxy $proxyStr")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        jobs.awaitAll()

        val success = htmls.values.firstOrNull()
        if (success != null) {
            println("HTML LENGTH: " + success.length)
            File("/tmp/success.html").writeText(success)
        } else {
            println("ALL PROXIES FAILED OR BLOCKED")
        }
    }
}
