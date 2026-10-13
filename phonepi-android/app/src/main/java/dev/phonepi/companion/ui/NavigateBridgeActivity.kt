package dev.phonepi.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.nav.NavigationLauncher
import dev.phonepi.companion.nav.NavigationPlan
import dev.phonepi.companion.nav.NavigationUris

/**
 * Trampoline started from the navigation full-screen notification. Running as
 * an activity opts into background activity start, then this fires the
 * allowlisted Maps intent and finishes.
 */
class NavigateBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
        val preferred = intent.getStringExtra(EXTRA_URI)
        val uris = buildList {
            if (!preferred.isNullOrBlank()) add(preferred)
            if (query.isNotBlank()) addAll(
                NavigationUris.candidates(
                    NavigationPlan(query = query, display = query, sourceUrl = sourceUrl),
                ),
            )
        }.distinct()
        val started = NavigationLauncher.tryStart(this, uris)
        ActivityLog.logNavigation(
            if (started != null) "Maps opened via notification ($started)" else "Maps did not start from notification",
            if (started != null) "success" else "error",
        )
        finish()
    }

    companion object {
        const val EXTRA_QUERY = "query"
        const val EXTRA_SOURCE_URL = "source_url"
        const val EXTRA_URI = "uri"

        fun intent(context: Context, query: String, sourceUrl: String?, uri: String): Intent =
            Intent(context, NavigateBridgeActivity::class.java).apply {
                putExtra(EXTRA_QUERY, query)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_URI, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
