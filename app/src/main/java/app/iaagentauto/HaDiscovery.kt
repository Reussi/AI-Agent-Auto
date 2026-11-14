package app.iaagentauto

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Findet Home Assistant automatisch im WLAN. Home Assistant meldet sich per mDNS/Zeroconf
 * als Dienst „_home-assistant._tcp“ an und nennt dabei seine Adressen (lokal und ggf. extern).
 */
object HaDiscovery {

    data class Found(val name: String, val localUrls: List<String>, val externalUrl: String?)

    private const val SERVICE_TYPE = "_home-assistant._tcp."

    suspend fun discover(context: Context, timeoutMs: Long = 6_000): List<Found> = withContext(Dispatchers.Main) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@withContext emptyList()
        val results = Collections.synchronizedList(mutableListOf<Found>())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        results.add(toFound(info))
                    }
                })
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(timeoutMs)
        } catch (_: Exception) {
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        results.distinctBy { it.localUrls.firstOrNull() }
    }

    private fun toFound(info: NsdServiceInfo): Found {
        val attrs = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        val urls = mutableListOf<String>()
        // Von Home Assistant angekündigte Adressen bevorzugen
        listOf("internal_url", "base_url", "location").forEach { key ->
            attrs[key]?.takeIf { it.startsWith("http") }?.let { urls.add(HomeAssistant.normalizeUrl(it)) }
        }
        // Sonst aus IP-Adresse und Port zusammensetzen
        @Suppress("DEPRECATION")
        val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull()?.hostAddress else info.host?.hostAddress
        if (host != null) {
            val h = if (host.contains(':')) "[$host]" else host
            urls.add("http://$h:${info.port}")
        }
        val external = attrs["external_url"]?.takeIf { it.startsWith("http") }?.let { HomeAssistant.normalizeUrl(it) }
        return Found(info.serviceName ?: "Home Assistant", urls.distinct(), external)
    }
}
