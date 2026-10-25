package dev.phonepi.companion.wear

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WearMessageDispatcherTest {
    @Test
    fun promptForwardsSanitizedText() = runBlocking {
        val fake = FakeActions()
        val dispatcher = WearMessageDispatcher(fake)
        val payload = String(WearProtocol.encodePrompt("  set a timer  ", "voice"), Charsets.UTF_8)
        val ack = dispatcher.dispatch(WearPaths.PROMPT, payload)
        assertTrue(ack.ok)
        assertEquals("prompt", ack.action)
        assertEquals(listOf("set a timer" to "voice"), fake.prompts)
    }

    @Test
    fun blankPromptRejected() = runBlocking {
        val fake = FakeActions()
        val dispatcher = WearMessageDispatcher(fake)
        val ack = dispatcher.dispatch(WearPaths.PROMPT, """{"text":"   "}""")
        assertFalse(ack.ok)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test
    fun promptIgnoresSecretKeysInPayload() = runBlocking {
        val fake = FakeActions()
        val dispatcher = WearMessageDispatcher(fake)
        val payload = """{"text":"hello","token":"should-not-be-read","TS_AUTHKEY":"nope"}"""
        assertTrue(WearProtocol.payloadContainsSecretKey(payload))
        val ack = dispatcher.dispatch(WearPaths.PROMPT, payload)
        assertTrue(ack.ok)
        assertEquals("hello", fake.prompts.single().first)
    }

    @Test
    fun quietValidatesRange() = runBlocking {
        val fake = FakeActions()
        val dispatcher = WearMessageDispatcher(fake)
        assertFalse(dispatcher.dispatch(WearPaths.QUIET, """{"minutes":0}""").ok)
        assertFalse(dispatcher.dispatch(WearPaths.QUIET, """{"minutes":721}""").ok)
        val ack = dispatcher.dispatch(WearPaths.QUIET, String(WearProtocol.encodeQuiet(60), Charsets.UTF_8))
        assertTrue(ack.ok)
        assertEquals(listOf(60), fake.quiet)
    }

    @Test
    fun imOkSubmitsFixedPrompt() = runBlocking {
        val fake = FakeActions()
        val dispatcher = WearMessageDispatcher(fake)
        val ack = dispatcher.dispatch(WearPaths.IM_OK, "")
        assertTrue(ack.ok)
        assertEquals("im_ok", ack.action)
        assertEquals(WearProtocol.IM_OK_TEXT, fake.prompts.single().first)
        assertEquals("im_ok", fake.prompts.single().second)
    }

    @Test
    fun nextAlarmUsesInjectedClockAndPresence() = runBlocking {
        val zone = ZoneId.of("America/Los_Angeles")
        val now = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val alarm = ZonedDateTime.of(2026, 10, 25, 18, 30, 0, 0, zone).toInstant().toEpochMilli()
        val dispatcher = WearMessageDispatcher(
            actions = FakeActions(),
            nextAlarmEpochMs = { alarm },
            presence = PresenceLabelSource { "home" },
            formatAlarm = { trigger, at -> NextAlarmFormat.longText(trigger, "home", at, zone) },
            nowMs = { now },
        )
        val ack = dispatcher.dispatch(WearPaths.NEXT_ALARM, "")
        assertTrue(ack.ok)
        assertEquals(alarm, ack.nextAlarmEpochMs)
        assertEquals("home", ack.presenceLabel)
        assertTrue(ack.message.contains("6:30"))
        assertTrue(ack.message.contains("home"))
    }

    @Test
    fun unknownPathFailsClosed() = runBlocking {
        val ack = WearMessageDispatcher(FakeActions()).dispatch("/other", "")
        assertFalse(ack.ok)
        assertEquals("unknown", ack.action)
    }

    @Test
    fun inboundPromptFrameHasNoToken() {
        val frame = WearProtocol.inboundPromptFrame("wear-1", "hello", "ask", 1L)
        assertTrue(frame.contains("\"type\":\"inbound_prompt\""))
        assertFalse(frame.contains("token"))
        assertFalse(frame.contains("TS_AUTHKEY"))
        assertFalse(frame.contains("authkey"))
    }

    @Test
    fun adapterQueuesWhenSocketDown() = runBlocking {
        val queued = mutableListOf<String>()
        val adapter = WearInboundActionsAdapter(
            sendFrame = { false },
            enqueueFrame = { queued += it },
            quiet = { WearActionResult(true, "quiet") },
            log = { _, _ -> },
        )
        val result = adapter.submitPrompt("from the watch", "keyboard")
        assertTrue(result.ok)
        assertEquals("queued", result.message)
        assertEquals(1, queued.size)
        assertTrue(queued.single().contains("inbound_prompt"))
        assertFalse(queued.single().contains("token"))
        assertTrue(queued.single().contains("from the watch"))
    }

    @Test
    fun adapterForwardsWhenSocketUp() = runBlocking {
        val sent = mutableListOf<String>()
        val adapter = WearInboundActionsAdapter(
            sendFrame = { sent += it; true },
            enqueueFrame = { error("should not queue") },
            quiet = { WearActionResult(true, "quiet") },
            log = { _, _ -> },
        )
        val result = adapter.submitPrompt("ping", "ask")
        assertEquals("forwarded", result.message)
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("\"source\":\"ask\""))
    }

    @Test
    fun nextAlarmFormatTodayTomorrowAndNone() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val today = ZonedDateTime.of(2026, 10, 25, 18, 5, 0, 0, zone).toInstant().toEpochMilli()
        val tomorrow = ZonedDateTime.of(2026, 10, 26, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("No alarm", NextAlarmFormat.shortText(null, now, zone))
        assertEquals("6:05 PM", NextAlarmFormat.shortText(today, now, zone))
        assertTrue(NextAlarmFormat.shortText(tomorrow, now, zone).startsWith("Tom "))
        assertEquals("6:05 PM · home", NextAlarmFormat.longText(today, "home", now, zone))
    }

    private class FakeActions : WearInboundActions {
        val prompts = mutableListOf<Pair<String, String>>()
        val quiet = mutableListOf<Int>()

        override suspend fun submitPrompt(text: String, source: String): WearActionResult {
            prompts += text to source
            return WearActionResult(true, "ok")
        }

        override suspend fun quietForMinutes(minutes: Int): WearActionResult {
            quiet += minutes
            return WearActionResult(true, "quiet ${minutes}m")
        }
    }
}
