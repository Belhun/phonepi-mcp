package dev.phonepi.companion.nav

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class NavigationPlan(
    val query: String,
    val display: String,
    val sourceUrl: String? = null,
    val fromSavedName: String? = null,
)

object NavigationQuery {
    fun fromMapsUrl(raw: String): NavigationPlan? {
        val text = raw.trim()
        if (text.isEmpty() || !MapsLink.isAllowed(text)) return null
        if (MapsLink.looksLikeNavigationScheme(text)) {
            val q = queryFromCustomScheme(text) ?: return null
            return NavigationPlan(query = q, display = q)
        }
        val uri = MapsLink.parseHttp(text) ?: return NavigationPlan(
            query = text,
            display = text,
            sourceUrl = text,
        )
        val params = queryMap(uri.rawQuery)
        val fromParam = firstNonBlank(
            params["q"],
            params["query"],
            params["destination"],
            params["daddr"],
            params["destination_place_id"]?.let { null },
        )
        val fromPath = placeOrSearchFromPath(uri.path)
        val fromAt = latLngFromPath(uri.path) ?: latLngFromFragment(uri.fragment)
        val query = firstNonBlank(fromParam, fromAt, fromPath) ?: text
        return NavigationPlan(
            query = query,
            display = fromPath ?: query,
            sourceUrl = text,
        )
    }

    fun fromCoordinates(lat: Double, lng: Double): NavigationPlan? {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val query = "$lat,$lng"
        return NavigationPlan(query = query, display = query)
    }

    fun fromAddress(address: String): NavigationPlan? {
        val text = address.trim()
        if (text.isEmpty()) return null
        if (MapsLink.parseHttp(text) != null) return fromMapsUrl(text)
        return NavigationPlan(query = text, display = text)
    }

    private fun queryFromCustomScheme(raw: String): String? {
        val lower = raw.trim()
        return when {
            lower.startsWith("google.navigation:", ignoreCase = true) ->
                queryMap(lower.substringAfter(":", "")).let { it["q"] ?: it.values.firstOrNull() }
            lower.startsWith("geo:", ignoreCase = true) -> {
                val rest = lower.substringAfter(":", "")
                val params = queryMap(rest.substringAfter("?", ""))
                params["q"] ?: rest.substringBefore("?").takeIf { it.isNotBlank() && it != "0,0" }
            }
            else -> null
        }
    }

    private fun placeOrSearchFromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        val marker = segments.indexOfFirst { it == "place" || it == "search" || it == "dir" }
        if (marker < 0 || marker + 1 >= segments.size) return null
        val raw = segments[marker + 1]
        if (raw.startsWith("@")) return null
        return decode(raw.replace('+', ' ')).takeIf { it.isNotBlank() }
    }

    private fun latLngFromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val at = path.indexOf("@")
        if (at < 0) return null
        return latLngToken(path.substring(at + 1))
    }

    private fun latLngFromFragment(fragment: String?): String? {
        if (fragment.isNullOrBlank()) return null
        val at = fragment.indexOf("@")
        if (at < 0) return null
        return latLngToken(fragment.substring(at + 1))
    }

    private fun latLngToken(raw: String): String? {
        val parts = raw.split(',', limit = 3)
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return "$lat,$lng"
    }

    private fun queryMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in raw.split('&', ';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val key = decode(part.substring(0, i)).lowercase()
            val value = decode(part.substring(i + 1)).replace('+', ' ').trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
