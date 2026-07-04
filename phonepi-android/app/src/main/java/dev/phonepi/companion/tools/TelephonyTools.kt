package dev.phonepi.companion.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Disabled — carrier SMS superseded by Google Messages gm_* tools on desktop.
/*
class SendSmsTool(private val context: Context) : ToolHandler {
    override val name = "send_sms"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError("SEND_SMS permission not granted")
        }
        val to = params.requireString("to") ?: return toolError("Missing required parameter: to")
        val message = params.requireString("message")
            ?: return toolError("Missing required parameter: message")
        if (message.isBlank()) return toolError("Message cannot be empty")
        val dest = normalizePhone(to) ?: return toolError("Invalid phone number")

        val simSlot = params.requireInt("simSlot")
        val subscriptionIdParam = params.requireInt("subscriptionId")
        val chosenSubId = SmsSubscriptionHelper.resolveSubscriptionId(
            context,
            subscriptionIdParam,
            simSlot,
        )
        if (simSlot != null && chosenSubId == null) {
            return toolError("No active SIM found for simSlot $simSlot")
        }
        if (subscriptionIdParam != null && chosenSubId == null) {
            return toolError("Invalid subscriptionId")
        }

        val sms = when {
            chosenSubId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                SmsSubscriptionHelper.smsManagerForSubscription(chosenSubId)
            else -> SmsManager.getDefault()
        }

        val parts = sms.divideMessage(message)
        if (parts.size == 1) {
            sms.sendTextMessage(dest, null, message, null, null)
        } else {
            sms.sendMultipartTextMessage(dest, null, parts, null, null)
        }

        val usedSubId = chosenSubId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        val subInfo = SmsSubscriptionHelper.subscriptionInfo(context, usedSubId)

        return success {
            put("to", dest)
            put("messageLength", message.length)
            put("parts", parts.size)
            put("subscriptionId", usedSubId)
            subInfo?.simSlot?.let { put("simSlot", it) }
            subInfo?.displayName?.let { put("simName", it) }
            if (chosenSubId == null) put("usedDefaultSim", true)
        }
    }
}

class ListSmsSubscriptionsTool(private val context: Context) : ToolHandler {
    override val name = "list_sms_subscriptions"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError(
                "READ_PHONE_STATE permission not granted — open PhonePi and tap Grant under SMS & SIMs",
            )
        }
        val subs = SmsSubscriptionHelper.list(context)
        if (subs.isEmpty()) {
            return toolError("No active SIM subscriptions found")
        }
        return success {
            put("count", subs.size)
            put("defaultSubscriptionId", SubscriptionManager.getDefaultSmsSubscriptionId())
            put(
                "subscriptions",
                buildJsonArray {
                    subs.forEach { sub ->
                        add(
                            buildJsonObject {
                                put("subscriptionId", sub.subscriptionId)
                                put("simSlot", sub.simSlot)
                                put("displayName", sub.displayName)
                                sub.carrierName?.let { put("carrierName", it) }
                                put("isDefaultSms", sub.isDefaultSms)
                            },
                        )
                    }
                },
            )
        }
    }
}
*/

class MakeCallTool(private val context: Context) : ToolHandler {
    override val name = "make_call"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError("CALL_PHONE permission not granted")
        }
        val to = params.requireString("to") ?: return toolError("Missing required parameter: to")
        val dest = normalizePhone(to) ?: return toolError("Invalid phone number")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$dest")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return success {
            put("to", dest)
            put(
                "note",
                "Calls always use the system default dialer/SIM — use Android settings to change default call SIM",
            )
        }
    }
}

private fun normalizePhone(raw: String): String? {
    val s = raw.trim().removePrefix("tel:")
    val digits = s.filter { it.isDigit() || it == '+' }
    if (digits.count { it.isDigit() } < 7) return null
    return digits
}
