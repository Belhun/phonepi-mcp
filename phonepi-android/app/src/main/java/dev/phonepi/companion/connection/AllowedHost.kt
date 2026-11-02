package dev.phonepi.companion.connection

import dev.phonepi.companion.BuildConfig

/**
 * Hosts that may receive the PhonePi token. Release: this tailnet's MagicDNS
 * (`PHONEPI_TAILNET_SUFFIX`, default `tailcbcc46.ts.net`), Tailscale CGNAT
 * `100.64.0.0/10`, Tailscale IPv6 `fd7a:115c:a1e0::/48`, and loopback.
 * A blank suffix falls back to MagicDNS suffixes of already-saved servers.
 * Debug builds also allow RFC1918 LAN addresses for local testing.
 */
object AllowedHost {
    /**
     * DNS hostname: 1–253 chars, labels 1–63, LDH only. Applied after lowercase
     * and a single trailing-dot strip. Rejects `@ / \ ? # %`, space, and
     * non-ASCII so OkHttp cannot end the host early (`HttpUrl` delimiters
     * `@/\\?#`) and still leave a suffix that matches the tailnet pin.
     */
    private val ASCII_HOSTNAME = Regex(
        "^(?=.{1,253}\$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\$",
    )

    fun configuredSuffix(): String =
        BuildConfig.PHONEPI_TAILNET_SUFFIX.trim().trimStart('.').lowercase()

    /**
     * ASCII hostname matching [ASCII_HOSTNAME], or a dotted IPv4 / IPv6
     * literal. Zone IDs (`%`) are refused: PhonePi never needs link-local.
     */
    fun isValidHostLiteral(host: String): Boolean {
        val value = literalKey(host)
        if (value.isEmpty()) return false
        for (ch in value) {
            if (ch.code > 127) return false
            if (ch.isWhitespace()) return false
            if (ch == '@' || ch == '/' || ch == '\\' || ch == '?' || ch == '#' || ch == '%') {
                return false
            }
        }
        if (ASCII_HOSTNAME.matches(value)) return true
        if (ipv4Parts(value) != null) return true
        if (expandIpv6(value) != null) return true
        return false
    }

    /** Bracket IPv6 so URL parsers do not treat a hextet as the port. */
    fun hostForUrl(host: String): String {
        val clean = host.trim().trimStart('[').trimEnd(']')
        return if (expandIpv6(clean.lowercase()) != null) "[$clean]" else clean
    }

    fun isAllowed(
        host: String,
        debug: Boolean = BuildConfig.DEBUG,
        suffix: String = configuredSuffix(),
        savedHosts: List<String> = emptyList(),
    ): Boolean {
        val clean = normalize(host)
        if (clean.isEmpty() || !isValidHostLiteral(clean)) return false
        if (isAllowlisted(clean, suffix = suffix, savedHosts = savedHosts)) return true
        return debug && isPrivateLan(clean)
    }

    fun isAllowlisted(
        host: String,
        suffix: String = configuredSuffix(),
        savedHosts: List<String> = emptyList(),
    ): Boolean {
        val clean = normalize(host)
        if (!isValidHostLiteral(clean)) return false
        return isLoopback(clean) ||
            isOnPinnedTailnet(clean, suffix = suffix, savedHosts = savedHosts) ||
            isTailscaleV4(clean) ||
            isTailscaleV6(clean)
    }

    /**
     * MagicDNS name on the pinned tailnet. `hp1.tailcbcc46.ts.net` matches
     * suffix `tailcbcc46.ts.net`. `x.attacker.ts.net` does not. A `#`, `?`,
     * `\` or `@` in the string is not a hostname, even if the text ends with
     * the suffix (OkHttp would dial the left-hand side).
     */
    fun isOnPinnedTailnet(
        host: String,
        suffix: String = configuredSuffix(),
        savedHosts: List<String> = emptyList(),
    ): Boolean {
        val clean = normalize(host).lowercase()
        if (!isValidHostLiteral(clean)) return false
        val suffixes = effectiveSuffixes(suffix, savedHosts)
        return suffixes.any { s -> clean == s || clean.endsWith(".$s") }
    }

    /** Release builds refuse a blank token. Debug still allows loopback/LAN without one. */
    fun mayConnect(
        host: String,
        token: String,
        debug: Boolean = BuildConfig.DEBUG,
        suffix: String = configuredSuffix(),
        savedHosts: List<String> = emptyList(),
    ): Boolean {
        if (!isAllowed(host, debug = debug, suffix = suffix, savedHosts = savedHosts)) return false
        if (token.isBlank() && !debug) return false
        return true
    }

    fun suffixesFromSaved(hosts: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (host in hosts) {
            val lower = normalize(host).lowercase()
            if (!isValidHostLiteral(lower)) continue
            if (!lower.endsWith(".ts.net")) continue
            val firstDot = lower.indexOf('.')
            if (firstDot <= 0 || firstDot == lower.length - 1) continue
            out.add(lower.substring(firstDot + 1))
        }
        return out.toList()
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

    private fun effectiveSuffixes(suffix: String, savedHosts: List<String>): List<String> {
        val pinned = suffix.trim().trimStart('.').lowercase()
        if (pinned.isNotEmpty()) return listOf(pinned)
        return suffixesFromSaved(savedHosts)
    }

    private fun literalKey(host: String): String {
        var value = host.trim().trimStart('[').trimEnd(']').lowercase()
        if (value.endsWith('.')) value = value.dropLast(1)
        return value
    }

    private fun normalize(host: String): String {
        var value = host.trim().trimStart('[').trimEnd(']')
        if (value.endsWith('.')) value = value.dropLast(1)
        return TailscaleNet.canonicalHost(value)
    }

    private fun isLoopback(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "127.0.0.1" ||
            lower == "::1" ||
            lower == "localhost" ||
            lower == "0:0:0:0:0:0:0:1"
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
    "PhonePi only connects to this tailnet, Tailscale IPs, or loopback, not $host",
)

class LinkTokenNotOnTailnetException(val host: String) : IllegalArgumentException(
    "Refusing a setup-link token for $host; only this tailnet may supply a token",
)
