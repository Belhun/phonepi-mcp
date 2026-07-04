package dev.phonepi.companion.tools

import android.content.Context
import android.os.BatteryManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** get_battery_level: current charge percent and charging state. No permission needed. */
class BatteryTool(private val context: Context) : ToolHandler {

    override val name = "get_battery_level"

    override suspend fun handle(@Suppress("UNUSED_PARAMETER") params: JsonObject): JsonObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return success {
            put("level", level)
            put("isCharging", charging)
            put("chargingStatus", if (charging) "charging" else "discharging")
        }
    }
}
