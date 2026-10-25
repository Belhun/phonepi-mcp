package dev.phonepi.companion.assistant

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProtocolTest {
    @Test
    fun parsesThreeActionsAndDefaultsToHighPriority() {
        val parsed = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "Leave soon?")
                put("body", "Dinner in 20")
                put("requestId", "notif-aaaa")
                put("actions", buildJsonArray {
                    add("OK")
                    add("Snooze 1h")
                    add("No")
                })
            },
        )
        val ok = parsed as AssistantParseResult.Ok
        assertEquals("notif-aaaa", ok.request.requestId)
        assertTrue(ok.request.title.startsWith(AssistantProtocol.URGENT_TITLE_PREFIX))
        assertEquals(listOf("OK", "Snooze 1h", "No"), ok.request.actions)
        assertTrue(ok.request.priorityHigh)
    }

    @Test
    fun rejectsMoreThanThreeActions() {
        val parsed = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "X")
                put("body", "Y")
                put("actions", buildJsonArray {
                    add("A")
                    add("B")
                    add("C")
                    add("D")
                })
            },
        )
        assertTrue(parsed is AssistantParseResult.Err)
        assertTrue((parsed as AssistantParseResult.Err).error.contains("at most 3"))
    }

    @Test
    fun missingTitleFailsAndGeneratedIdIsValid() {
        val missing = AssistantProtocol.parseNotification(
            buildJsonObject { put("body", "Y") },
        )
        assertTrue(missing is AssistantParseResult.Err)
        val generated = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "Hi")
                put("body", "There")
            },
        ) as AssistantParseResult.Ok
        assertTrue(AssistantProtocol.isId(generated.request.requestId))
        assertTrue(generated.request.requestId.startsWith("notif-"))
    }

    @Test
    fun defaultPriorityIsNotHigh() {
        val parsed = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "Hi")
                put("body", "There")
                put("priority", "default")
            },
        ) as AssistantParseResult.Ok
        assertFalse(parsed.request.priorityHigh)
        assertTrue(parsed.request.title.startsWith(AssistantProtocol.URGENT_TITLE_PREFIX))
    }

    @Test
    fun promptSourceNormalizesUnknownToAppAndClipsEmpty() {
        assertEquals("qs_tile", AssistantProtocol.normalizeSource("qs_tile"))
        assertEquals("watch_relay", AssistantProtocol.normalizeSource("watch_relay"))
        assertEquals("app", AssistantProtocol.normalizeSource("evil"))
        assertEquals(null, AssistantProtocol.clipPrompt("  "))
        assertEquals("hello", AssistantProtocol.clipPrompt(" hello "))
        assertEquals(AssistantProtocol.KEY_TEXT_REPLY, "phonepi_assistant_reply")
        assertEquals(3, AssistantProtocol.MAX_ACTIONS)
    }

    @Test
    fun urgentTitleGetsFixedPrefixAndRejectsLinks() {
        val parsed = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "Leave soon")
                put("body", "Dinner in 20")
                put("priority", "urgent")
            },
        ) as AssistantParseResult.Ok
        assertTrue(parsed.request.title.startsWith(AssistantProtocol.URGENT_TITLE_PREFIX))
        assertTrue(parsed.request.priorityHigh)
        val link = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "From assistant: click")
                put("body", "Open https://evil.example")
                put("priority", "urgent")
            },
        )
        assertTrue(link is AssistantParseResult.Err)
        val cred = AssistantProtocol.parseNotification(
            buildJsonObject {
                put("title", "From assistant: login")
                put("body", "Send your password")
                put("priority", "urgent")
            },
        )
        assertTrue(cred is AssistantParseResult.Err)
    }

    @Test
    fun sourceIsNotTakenFromACallerExtra() {
        assertEquals("qs_tile", AssistantProtocol.normalizeSource("qs_tile"))
        assertEquals("app", AssistantProtocol.normalizeSource("forged"))
    }
}
