package dev.phonepi.companion.connection

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * Pixel Private DNS (CleanBrowsing Family) breaks Tailscale MagicDNS.
 * TCP to the 100.x address still works if SNI is the *.ts.net name.
 * Same approach as Odysseus PhoneApp (`ody_http_factory_io.dart`).
 */
object TailscaleNet {
    val hostToIp: Map<String, String> = mapOf(
        "dell-mini-pc.tailcbcc46.ts.net" to "100.79.4.36",
        "hp1.tailcbcc46.ts.net" to "100.108.138.113",
    )
    val ipToHost: Map<String, String> = hostToIp.entries.associate { it.value to it.key }

    fun canonicalHost(host: String): String {
        val trimmed = host.trim().trimStart('[').trimEnd(']')
        return ipToHost[trimmed] ?: trimmed
    }

    fun ipv4Bytes(ip: String): ByteArray {
        val parts = ip.split('.')
        require(parts.size == 4) { "not ipv4: $ip" }
        return byteArrayOf(
            parts[0].toInt().toByte(),
            parts[1].toInt().toByte(),
            parts[2].toInt().toByte(),
            parts[3].toInt().toByte(),
        )
    }

    fun sourceAddress(): InetAddress? {
        val nics = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in nics) {
            val addrs = nic.inetAddresses ?: continue
            for (addr in addrs) {
                if (addr is Inet4Address && addr.hostAddress?.startsWith("100.") == true) {
                    return addr
                }
            }
        }
        return null
    }
}

object TailscaleDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val mapped = TailscaleNet.hostToIp[hostname.lowercase()]
        if (mapped != null) {
            return listOf(InetAddress.getByAddress(hostname, TailscaleNet.ipv4Bytes(mapped)))
        }
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            throw e
        }
    }
}

/** Bind to the Tailscale 100.x address only when the destination is CGNAT. */
class TailscaleSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = TailscaleAwareSocket()

    override fun createSocket(host: String?, port: Int): Socket {
        val socket = TailscaleAwareSocket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket {
        val socket = Socket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        val socket = TailscaleAwareSocket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(
        host: InetAddress?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket {
        val socket = Socket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }
}

class TailscaleAwareSocket : Socket() {
    override fun connect(endpoint: SocketAddress?) {
        maybeBind(endpoint)
        super.connect(endpoint)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        maybeBind(endpoint)
        super.connect(endpoint, timeout)
    }

    private fun maybeBind(endpoint: SocketAddress?) {
        if (isBound) return
        val dest = (endpoint as? InetSocketAddress)?.address?.hostAddress ?: return
        if (!dest.startsWith("100.")) return
        try {
            val src = TailscaleNet.sourceAddress() ?: return
            bind(InetSocketAddress(src, 0))
        } catch (_: Exception) {
            // Unbound still routes for many setups; Dns + SNI is the important fix.
        }
    }
}
