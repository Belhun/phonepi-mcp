package dev.phonepi.companion.wear

/**
 * Pure dispatch from a Data Layer path+payload to [WearInboundActions].
 * Unit-tested; no Android types.
 */
class WearMessageDispatcher(
    private val actions: WearInboundActions,
    private val nextAlarmEpochMs: () -> Long? = { null },
    private val presence: PresenceLabelSource = PresenceLabelSource.Stub,
    private val formatAlarm: (Long?, Long) -> String = { trigger, now ->
        NextAlarmFormat.shortText(trigger, now)
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun dispatch(path: String, payloadUtf8: String): WearAck {
        return when (val message = WearProtocol.parse(path, payloadUtf8)) {
            is WearInboundMessage.SubmitPrompt -> {
                val clean = WearProtocol.sanitizePrompt(message.text)
                    ?: return WearAck(false, "prompt", "empty prompt")
                val result = actions.submitPrompt(clean, message.source)
                WearAck(result.ok, "prompt", result.message)
            }
            is WearInboundMessage.QuietFor -> {
                val minutes = WearProtocol.validateQuietMinutes(message.minutes)
                    ?: return WearAck(false, "quiet", "minutes must be 1..720")
                val result = actions.quietForMinutes(minutes)
                WearAck(result.ok, "quiet", result.message)
            }
            is WearInboundMessage.ImOk -> {
                val result = actions.submitPrompt(WearProtocol.IM_OK_TEXT, "im_ok")
                WearAck(result.ok, "im_ok", result.message)
            }
            is WearInboundMessage.NextAlarm -> {
                val trigger = nextAlarmEpochMs()
                val now = nowMs()
                WearAck(
                    ok = true,
                    action = "next_alarm",
                    message = formatAlarm(trigger, now),
                    nextAlarmEpochMs = trigger,
                    presenceLabel = presence.currentLabel(),
                )
            }
            is WearInboundMessage.Unknown ->
                WearAck(false, "unknown", "unknown path")
        }
    }
}
