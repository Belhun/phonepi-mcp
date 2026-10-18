package dev.phonepi.companion.textsend

import android.content.Context

/** On-phone switches. Both send switches default off. Off rejects new requests. */
object SendTextPrefs {
    private const val PREFS = "phonepi_send_text_prefs"
    private const val ALLOW_REQUESTS = "allow_requests"
    private const val ALLOW_ATTRIBUTED = "allow_attributed"
    private const val DRIVING_MODE = "driving_mode"
    private const val BUDGET = "budget"

    fun allowRequests(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOW_REQUESTS, false)

    fun setAllowRequests(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALLOW_REQUESTS, enabled)
            .apply()
    }

    fun allowAttributed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOW_ATTRIBUTED, false)

    fun setAllowAttributed(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALLOW_ATTRIBUTED, enabled)
            .apply()
    }

    fun drivingMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DRIVING_MODE, false)

    fun setDrivingMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DRIVING_MODE, enabled)
            .apply()
    }

    fun loadBudget(context: Context, now: () -> Long = { System.currentTimeMillis() }): SendTextBudget {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BUDGET, null)
        val initial = mutableMapOf<String, List<Long>>()
        if (!raw.isNullOrBlank()) {
            for (part in raw.split(';')) {
                val bits = part.split('=', limit = 2)
                if (bits.size != 2) continue
                val stamps = bits[1].split(',').mapNotNull { it.toLongOrNull() }
                initial[bits[0]] = stamps
            }
        }
        return SendTextBudget(now, initial)
    }

    fun saveBudget(context: Context, budget: SendTextBudget) {
        val raw = budget.export().entries.joinToString(";") { (mode, stamps) ->
            "$mode=${stamps.joinToString(",")}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BUDGET, raw)
            .apply()
    }
}
