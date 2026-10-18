package dev.phonepi.companion.textsend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SendTextScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun arm(context: Context, id: String, expiresAt: Long) {
        jobs.remove(id)?.cancel()
        val wait = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val app = context.applicationContext
        jobs[id] = scope.launch {
            delay(wait)
            SendTextController.expire(app, id)
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }
}

object SendTextController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val smsByApp = ConcurrentHashMap<String, SmsTextSender>()

    fun rearm(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        for (record in SendTextStore.all(app)) {
            if (record.status != "pending" || record.handedToCarrier) continue
            if (now >= record.expiresAt) expire(app, record.id) else SendTextScheduler.arm(app, record.id, record.expiresAt)
        }
        if (SendTextPrefs.allowRequests(app)) InVehicleMonitor.start(app)
    }

    fun rejectOutstanding(context: Context) {
        val app = context.applicationContext
        for (record in SendTextStore.all(app)) {
            if (record.status == "pending" && !record.handedToCarrier) cancel(app, record.id)
        }
        InVehicleMonitor.stop(app)
        SendTextSpeechService.stop(app)
    }

    suspend fun request(
        context: Context,
        recipient: String,
        message: String,
        modeRaw: String?,
    ): JsonObject {
        val app = context.applicationContext
        val mode = if (modeRaw == SendTextPolicy.MODE_ATTRIBUTED) {
            SendTextPolicy.MODE_ATTRIBUTED
        } else {
            SendTextPolicy.MODE_AS_ALEX
        }
        if (!SendTextPrefs.allowRequests(app)) {
            return reject(
                app,
                mode = mode,
                recipient = recipient,
                message = message,
                error = "Allow bots to request texts is off",
                outcome = "rejected",
            )
        }
        if (message.isBlank() || message.length > SendTextPolicy.MESSAGE_MAX) {
            return reject(app, mode, recipient, message, "message must be 1 to 1000 characters", "rejected")
        }
        if (!has(app, Manifest.permission.SEND_SMS)) {
            return reject(app, mode, recipient, message, "SEND_SMS permission not granted", "failed", "SEND_SMS")
        }
        if (!canNotify(app)) {
            return reject(
                app,
                mode,
                recipient,
                message,
                "Notification permission not granted",
                "failed",
                "POST_NOTIFICATIONS",
            )
        }
        val budget = SendTextPrefs.loadBudget(app)
        when (val hit = budget.allow(mode)) {
            is SendTextBudget.BudgetResult.Denied -> {
                SendTextPrefs.saveBudget(app, budget)
                return reject(
                    app,
                    mode,
                    recipient,
                    message,
                    "Rate limited: request_send_text. Try again in ${hit.retryAfterSec}s",
                    "rejected",
                )
            }
            SendTextBudget.BudgetResult.Allowed -> SendTextPrefs.saveBudget(app, budget)
        }
        if (!SendTextPolicy.isDirectNumber(recipient) && !has(app, Manifest.permission.READ_CONTACTS)) {
            return reject(app, mode, recipient, message, "READ_CONTACTS permission not granted", "failed", "READ_CONTACTS")
        }
        val resolved = SendTextPolicy.resolveRecipient(
            recipient,
            AndroidContactDirectory(app).listNumbers(),
        )
        when (resolved) {
            is RecipientResolution.Rejected -> {
                return reject(app, mode, recipient, message, resolved.error, "rejected")
            }
            is RecipientResolution.Clarify -> {
                val id = newId()
                val now = System.currentTimeMillis()
                SendTextStore.save(
                    app,
                    SendRecord(
                        id = id,
                        mode = mode,
                        recipientName = recipient.trim(),
                        number = "",
                        message = message,
                        createdAt = now,
                        expiresAt = now,
                        status = "needs_clarification",
                    ),
                )
                SendTextEvents.emit(
                    context = app,
                    outcome = "needs_clarification",
                    mode = mode,
                    id = id,
                    recipientName = recipient.trim(),
                    number = "",
                    message = message,
                    confirmMethod = null,
                    error = "Send nothing until the recipient is one person.",
                    candidates = resolved.candidates,
                )
                return buildJsonObject {
                    put("status", "needs_clarification")
                    put("sent", false)
                    put("id", id)
                    put("mode", mode)
                    put("message", message)
                    put(
                        "candidates",
                        buildJsonArray {
                            resolved.candidates.forEach { candidate ->
                                add(
                                    buildJsonObject {
                                        put("name", candidate.name)
                                        put("numberMasked", candidate.numberMasked)
                                    },
                                )
                            }
                        },
                    )
                }
            }
            is RecipientResolution.One -> {
                return openSend(app, mode, resolved, message)
            }
        }
    }

    fun status(context: Context, id: String): JsonObject {
        val app = context.applicationContext
        val record = SendTextStore.find(app, id)
            ?: return buildJsonObject {
                put("status", "failed")
                put("sent", false)
                put("error", "Unknown send id")
            }
        if (record.status == "pending" && !record.handedToCarrier &&
            System.currentTimeMillis() >= record.expiresAt
        ) {
            expire(app, id)
            val expired = SendTextStore.find(app, id) ?: record.copy(status = "expired")
            return toJson(expired)
        }
        return toJson(record)
    }

    fun confirmAsync(context: Context, id: String, method: String, done: () -> Unit = {}) {
        scope.launch {
            try {
                confirm(context.applicationContext, id, method)
            } finally {
                done()
            }
        }
    }

    suspend fun confirm(context: Context, id: String, method: String) {
        val app = context.applicationContext
        val record = SendTextStore.find(app, id) ?: return
        if (record.handedToCarrier || record.status != "pending") return
        val now = System.currentTimeMillis()
        when (val decision = SendTextPolicy.decide(record, SendAction.Confirm(method), now)) {
            is SendDecision.Close -> close(app, record, decision.status, null)
            is SendDecision.Keep -> Unit
            is SendDecision.Send -> dispatch(app, record, decision)
        }
    }

    fun cancel(context: Context, id: String) {
        val app = context.applicationContext
        val record = SendTextStore.find(app, id) ?: return
        if (record.handedToCarrier || record.status != "pending") return
        val decision = SendTextPolicy.decide(record, SendAction.Cancel, System.currentTimeMillis())
        if (decision is SendDecision.Close) close(app, record, decision.status, "cancel")
    }

    fun expire(context: Context, id: String) {
        val app = context.applicationContext
        val record = SendTextStore.find(app, id) ?: return
        if (record.handedToCarrier || record.status != "pending") return
        val decision = SendTextPolicy.decide(record, SendAction.Expire, System.currentTimeMillis())
        if (decision is SendDecision.Close) close(app, record, decision.status, null)
    }

    private suspend fun openSend(
        app: Context,
        mode: String,
        resolved: RecipientResolution.One,
        message: String,
    ): JsonObject {
        val now = System.currentTimeMillis()
        val record = SendRecord(
            id = newId(),
            mode = mode,
            recipientName = resolved.name,
            number = resolved.number,
            message = message,
            createdAt = now,
            expiresAt = now + SendTextPolicy.TTL_MS,
        )
        if (mode == SendTextPolicy.MODE_ATTRIBUTED) {
            if (!SendTextPrefs.allowAttributed(app)) {
                return reject(
                    app,
                    mode,
                    resolved.name,
                    message,
                    "Allow assistant-signed texts without confirm is off",
                    "rejected",
                )
            }
            val body = SendTextPolicy.carrierText(mode, message)
            val decision = SendTextPolicy.decide(record, SendAction.Attributed(body), now)
            if (decision !is SendDecision.Send) {
                val failed = record.copy(status = "failed")
                SendTextStore.save(app, failed)
                SendTextEvents.emit(
                    app, "failed", mode, failed.id, failed.recipientName, failed.number,
                    message, null, "attributed text was missing its prefix",
                )
                return toJson(failed, "attributed text was missing its prefix")
            }
            return dispatch(app, record, decision)
        }
        SendTextStore.save(app, record)
        SendTextNotifications.postConfirm(app, record)
        SendTextScheduler.arm(app, record.id, record.expiresAt)
        SendTextEvents.emit(
            app, "pending", mode, record.id, record.recipientName, record.number,
            message, null,
        )
        if (DrivingProbe.isDriving(app)) {
            if (has(app, Manifest.permission.RECORD_AUDIO)) {
                try {
                    SendTextSpeechService.start(app, record.id)
                } catch (error: Exception) {
                    SendTextEvents.emit(
                        app, "pending", mode, record.id, record.recipientName, record.number,
                        message, null, "Spoken confirm did not start. Tap Send on the notification.",
                    )
                }
            } else {
                SendTextEvents.emit(
                    app, "pending", mode, record.id, record.recipientName, record.number,
                    message, null, "RECORD_AUDIO not granted. Tap Send on the notification.",
                )
            }
        }
        return toJson(record)
    }

    private suspend fun dispatch(app: Context, record: SendRecord, decision: SendDecision.Send): JsonObject {
        val sending = record.copy(handedToCarrier = true)
        SendTextStore.save(app, sending)
        SendTextScheduler.cancel(record.id)
        val outcome = sender(app).send(record.id, record.number, decision.carrierText)
        val finished = when (outcome) {
            SmsSendOutcome.Sent -> sending.copy(status = "sent", confirmMethod = decision.confirmMethod)
            is SmsSendOutcome.Failed -> sending.copy(status = "failed", confirmMethod = decision.confirmMethod)
            SmsSendOutcome.Unconfirmed -> sending.copy(confirmMethod = decision.confirmMethod)
        }
        SendTextStore.save(app, finished)
        if (finished.status == "sent") {
            SendTextNotifications.postSent(app, finished, decision.carrierText)
            SendTextEvents.emit(
                app, "sent", finished.mode, finished.id, finished.recipientName, finished.number,
                decision.carrierText, decision.confirmMethod,
            )
        } else if (finished.status == "failed") {
            SendTextNotifications.dismiss(app, finished.id)
            val error = (outcome as SmsSendOutcome.Failed).error
            SendTextEvents.emit(
                app, "failed", finished.mode, finished.id, finished.recipientName, finished.number,
                decision.carrierText, decision.confirmMethod, error,
            )
            return toJson(finished, error)
        } else {
            SendTextEvents.emit(
                app, "pending", finished.mode, finished.id, finished.recipientName, finished.number,
                decision.carrierText, decision.confirmMethod, "waiting for the carrier",
            )
        }
        return toJson(finished)
    }

    private fun close(app: Context, record: SendRecord, status: String, confirmMethod: String?) {
        val closed = record.copy(status = status, confirmMethod = confirmMethod ?: record.confirmMethod)
        SendTextStore.save(app, closed)
        SendTextScheduler.cancel(record.id)
        SendTextNotifications.dismiss(app, record.id)
        SendTextSpeechService.stop(app)
        SendTextEvents.emit(
            app, status, closed.mode, closed.id, closed.recipientName, closed.number,
            closed.message, confirmMethod,
        )
    }

    private fun reject(
        app: Context,
        mode: String,
        recipient: String,
        message: String,
        error: String,
        outcome: String,
        grantNeeded: String? = null,
    ): JsonObject {
        val id = newId()
        val now = System.currentTimeMillis()
        SendTextStore.save(
            app,
            SendRecord(
                id = id,
                mode = mode,
                recipientName = recipient.trim().ifEmpty { "?" },
                number = "",
                message = message,
                createdAt = now,
                expiresAt = now,
                status = outcome,
            ),
        )
        SendTextEvents.emit(
            app, outcome, mode, id, recipient.trim().ifEmpty { "?" }, "", message, null, error,
        )
        return buildJsonObject {
            put("status", outcome)
            put("sent", false)
            put("id", id)
            put("mode", mode)
            put("error", error)
            if (grantNeeded != null) put("grantNeeded", grantNeeded)
        }
    }

    private fun toJson(record: SendRecord, error: String? = null): JsonObject {
        val body = if (record.mode == SendTextPolicy.MODE_ATTRIBUTED &&
            (record.status == "sent" || record.handedToCarrier)
        ) {
            SendTextPolicy.carrierText(record.mode, record.message)
        } else {
            record.message
        }
        return buildJsonObject {
            put("status", record.status)
            put("sent", record.status == "sent")
            put("id", record.id)
            put("mode", record.mode)
            put("recipientName", record.recipientName)
            put("numberMasked", if (record.number.isBlank()) "" else SendTextPolicy.maskPhone(record.number))
            put("message", body)
            put("requiresConfirmation", record.mode == SendTextPolicy.MODE_AS_ALEX && record.status == "pending")
            record.confirmMethod?.let { put("confirmMethod", it) }
            put("expiresAt", record.expiresAt)
            put("handedToCarrier", record.handedToCarrier)
            if (error != null) put("error", error)
        }
    }

    private fun sender(context: Context): SmsTextSender =
        smsByApp.getOrPut(context.packageName) { SmsTextSender(context) }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return has(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun newId(): String = "send-" + UUID.randomUUID().toString()
}
