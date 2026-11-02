package dev.phonepi.companion.connection

import dev.phonepi.companion.BuildConfig

/**
 * Hosts that may receive the PhonePi token. Release: `*.ts.net`, Tailscale
 * CGNAT `100.64.0.0/10`, Tailscale IPv6 `fd7a:115c:a1e0::/48`, and loopback.
 * Debug builds also allow RFC1918 LAN addresses for local testing.
 */
object AllowedHost {
    fun isAllowed(host: String, debug: Boolean = BuildConfig.DEBUG): Boolean {
        val clean = normalize(host)
        if (clean.isEmpty()) return false
        if (isAllowlisted(clean)) return true
        return debug && isPrivateLan(clean)
    }

    fun isAllowlisted(host: String): Boolean {
        val clean = normalize(host)
        return isLoopback(clean) || isTsNet(clean) || isTailscaleV4(clean) || isTailscaleV6(clean)
    }

    fun isPrivateLan(host: String): Boolean {
        val parts = ipv4Parts(normalize(host)) ?: return false
        val a = parts[0]
        val b = parts[1]
        if (a == 10) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        return false
    }

    private fun normalize(host: String): String {
        var value = host.trim().trimStart('[').trimEnd(']')
        if (value.endsWith('.')) value = value.dropLast(1)
        val zone = value.indexOf('%')
        if (zone >= 0) value = value.substring(0, zone)
        return TailscaleNet.canonicalHost(value)
    }

    private fun isLoopback(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "127.0.0.1" ||
            lower == "::1" ||
            lower == "localhost" ||
            lower == "0:0:0:0:0:0:0:1"
    }

    private fun isTsNet(host: String): Boolean {
        val lower = host.lowercase()
        return lower.endsWith(".ts.net") && lower.length > ".ts.net".length
    }

    private fun isTailscaleV4(host: String): Boolean {
        val parts = ipv4Parts(host) ?: return false
        return parts[0] == 100 && parts[1] in 64..127
    }

    private fun isTailscaleV6(host: String): Boolean {
        val hextets = expandIpv6(host) ?: return false
        return hextets[0] == 0xfd7a && hextets[1] == 0x115c && hextets[2] == 0xa1e0
    }

    private fun ipv4Parts(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            if (parts[i].length > 1 && parts[i].startsWith('0')) return null
            nums[i] = n
        }
        return nums
    }

    private fun expandIpv6(host: String): IntArray? {
        val lower = host.lowercase()
        if (':' !in lower) return null
        if (":::" in lower) return null
        val double = lower.indexOf("::")
        val groups: List<String> = if (double >= 0) {
            if (lower.indexOf("::", double + 2) >= 0) return null
            val left = if (double == 0) emptyList() else lower.substring(0, double).split(':')
            val right = if (double + 2 >= lower.length) {
                emptyList()
            } else {
                lower.substring(double + 2).split(':')
            }
            if (left.size + right.size > 8) return null
            left + List(8 - left.size - right.size) { "0" } + right
        } else {
            lower.split(':')
        }
        if (groups.size != 8) return null
        val out = IntArray(8)
        for (i in 0..7) {
            val part = groups[i]
            if (part.isEmpty() || part.length > 4) return null
            if (!part.all { it in '0'..'9' || it in 'a'..'f' }) return null
            out[i] = part.toInt(16)
        }
        return out
    }
}

class DisallowedPhonePiHostException(val host: String) : IllegalArgumentException(
    "PhonePi only connects to Tailscale (*.ts.net / 100.x) or loopback, not $host",
)
