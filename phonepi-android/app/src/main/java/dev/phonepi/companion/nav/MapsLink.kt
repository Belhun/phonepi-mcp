package dev.phonepi.companion.nav

import java.net.URI

object MapsLink {
    private val HOSTS = setOf(
        "maps.google.com",
        "www.google.com",
        "google.com",
        "maps.app.goo.gl",
        "goo.gl",
        "www.google.co.uk",
        "www.google.ca",
    )

    fun isAllowed(raw: String): Boolean {
        val uri = parseHttp(raw) ?: return looksLikeNavigationScheme(raw)
        val host = uri.host?.lowercase()?.trim('.') ?: return false
        if (host !in HOSTS && !host.endsWith(".google.com")) return false
        val path = uri.path.orEmpty().lowercase()
        return when (host) {
            "goo.gl" -> path.startsWith("/maps")
            "www.google.com", "google.com", "www.google.co.uk", "www.google.ca" ->
                path.startsWith("/maps")
            else -> true
        }
    }

    fun parseHttp(raw: String): URI? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            val uri = URI(text)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") null else uri
        } catch (_: Exception) {
            null
        }
    }

    fun looksLikeNavigationScheme(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return lower.startsWith("google.navigation:") || lower.startsWith("geo:")
    }
}
