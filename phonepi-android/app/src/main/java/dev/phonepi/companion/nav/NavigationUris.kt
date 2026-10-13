package dev.phonepi.companion.nav

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NavigationUris {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    fun encodeQuery(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun candidates(plan: NavigationPlan): List<String> {
        val encoded = encodeQuery(plan.query)
        val list = linkedSetOf(
            "google.navigation:q=$encoded",
            "geo:0,0?q=$encoded",
        )
        val source = plan.sourceUrl
        if (!source.isNullOrBlank() && MapsLink.isAllowed(source) && MapsLink.parseHttp(source) != null) {
            list.add(source)
        }
        list.add("https://www.google.com/maps/dir/?api=1&destination=$encoded")
        return list.toList()
    }
}
