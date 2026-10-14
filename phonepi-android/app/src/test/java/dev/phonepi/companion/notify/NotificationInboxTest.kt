package dev.phonepi.companion.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposedFieldTest {
    @Test
    fun emptyOrNullIsExplicitlyHidden() {
        val missing = ExposedField.of(null, "title")
        assertTrue(missing.hidden)
        assertEquals(HIDDEN_BY_ANDROID, missing.value)
        assertTrue(missing.reason!!.contains("title"))

        val blank = ExposedField.of("  ", "text")
        assertTrue(blank.hidden)
        assertEquals(HIDDEN_BY_ANDROID, blank.value)
    }

    @Test
    fun visibleTextIsUnchanged() {
        val field = ExposedField.of("  Hello  ", "title")
        assertFalse(field.hidden)
        assertEquals("Hello", field.value)
    }
}

class NotificationSharePolicyTest {
    @Test
    fun defaultSharesNothing() {
        val policy = NotificationSharePolicy()
        assertFalse(policy.shareAll)
        assertTrue(policy.allowedPackages.isEmpty())
        assertFalse(policy.allows("com.google.android.gm"))
    }

    @Test
    fun allowlistSharesOnlyPickedPackages() {
        val policy = NotificationSharePolicy(allowedPackages = setOf("com.gmail"))
        assertTrue(policy.allows("com.gmail"))
        assertFalse(policy.allows("com.whatsapp"))
    }

    @Test
    fun shareAllOverridesEmptyAllowlist() {
        val policy = NotificationSharePolicy(shareAll = true)
        assertTrue(policy.allows("any.pkg"))
    }
}

class NotificationInboxTest {
    private fun record(
        key: String,
        pkg: String,
        postedAt: Long,
        title: String? = "Hi",
        text: String? = "Body",
        appName: String? = "App",
        active: Boolean = true,
    ): NotificationRecord = NotificationRecord(
        key = key,
        packageName = pkg,
        appName = ExposedField.of(appName, "appName"),
        title = ExposedField.of(title, "title"),
        text = ExposedField.of(text, "text"),
        postedAt = postedAt,
        isActive = active,
    )

    @Test
    fun keepsLast200PlusActive() {
        val inbox = NotificationInbox(recentCap = 200)
        repeat(250) { i ->
            inbox.posted(record("k$i", "pkg.old", postedAt = i.toLong()))
        }
        assertEquals(200, inbox.recentCount())
        assertEquals(250, inbox.activeCount())
        val stillActive = inbox.query(
            NotificationQuery(limit = 300),
            NotificationSharePolicy(shareAll = true),
        )
        assertEquals(250, stillActive.matchedBeforeLimit)
        assertTrue(stillActive.notifications.any { it.key == "k0" })

        repeat(250) { i -> inbox.removed("k$i") }
        inbox.replaceActive(listOf(record("live", "pkg.live", postedAt = 9999L)))
        val afterSnapshot = inbox.query(
            NotificationQuery(limit = 300),
            NotificationSharePolicy(shareAll = true),
        )
        assertTrue(afterSnapshot.notifications.any { it.key == "live" })
        assertTrue(afterSnapshot.notifications.any { it.isActive })
        assertEquals(1, inbox.activeCount())
    }

    @Test
    fun removedStaysInRecentButNotActive() {
        val inbox = NotificationInbox()
        inbox.posted(record("k1", "pkg.a", postedAt = 10L))
        inbox.removed("k1")
        val result = inbox.query(
            NotificationQuery(limit = 10),
            NotificationSharePolicy(shareAll = true),
        )
        assertEquals(1, result.notifications.size)
        assertFalse(result.notifications[0].isActive)
        assertEquals(0, inbox.activeCount())
    }

    @Test
    fun defaultPolicyOmitsBodiesAndCountsThem() {
        val inbox = NotificationInbox()
        inbox.posted(record("k1", "com.gmail", postedAt = 1L))
        inbox.posted(record("k2", "com.whatsapp", postedAt = 2L))
        val result = inbox.query(NotificationQuery(), NotificationSharePolicy())
        assertTrue(result.notifications.isEmpty())
        assertEquals(2, result.omittedUnsharedCount)
    }

    @Test
    fun allowlistReturnsOnlyPickedApp() {
        val inbox = NotificationInbox()
        inbox.posted(record("k1", "com.gmail", postedAt = 1L, title = "Mail"))
        inbox.posted(record("k2", "com.whatsapp", postedAt = 2L, title = "Chat"))
        val result = inbox.query(
            NotificationQuery(),
            NotificationSharePolicy(allowedPackages = setOf("com.gmail")),
        )
        assertEquals(1, result.notifications.size)
        assertEquals("com.gmail", result.notifications[0].packageName)
        assertEquals(1, result.omittedUnsharedCount)
    }

    @Test
    fun filtersPackageSinceAndLimit() {
        val inbox = NotificationInbox()
        inbox.posted(record("a1", "com.gmail", postedAt = 100L))
        inbox.posted(record("a2", "com.gmail", postedAt = 200L))
        inbox.posted(record("a3", "com.gmail", postedAt = 300L))
        inbox.posted(record("b1", "com.maps", postedAt = 400L))
        val result = inbox.query(
            NotificationQuery(packageName = "com.gmail", sinceMs = 150L, limit = 1),
            NotificationSharePolicy(shareAll = true),
        )
        assertEquals(1, result.notifications.size)
        assertEquals("a3", result.notifications[0].key)
        assertTrue(result.truncated)
        assertEquals(2, result.matchedBeforeLimit)
        assertFalse(result.notifications.any { it.packageName == "com.maps" })
    }

    @Test
    fun hiddenFieldsAreReportedNotDropped() {
        val inbox = NotificationInbox()
        inbox.posted(record("k1", "com.bank", postedAt = 1L, title = null, text = null, appName = null))
        val row = inbox.query(
            NotificationQuery(),
            NotificationSharePolicy(shareAll = true),
        ).notifications.single()
        assertEquals(HIDDEN_BY_ANDROID, row.title.value)
        assertEquals(HIDDEN_BY_ANDROID, row.text.value)
        val names = row.hiddenFields().map { it.field }.toSet()
        assertTrue(names.contains("title"))
        assertTrue(names.contains("text"))
        assertTrue(names.contains("appName"))
    }

    @Test
    fun listAppsDoesNotIncludeBodies() {
        val inbox = NotificationInbox()
        inbox.posted(record("k1", "com.gmail", postedAt = 5L, title = "Secret", appName = "Gmail"))
        val listed = inbox.listApps(NotificationSharePolicy(allowedPackages = setOf("com.gmail")))
        assertEquals(1, listed.size)
        assertEquals("com.gmail", listed[0].packageName)
        assertEquals("Gmail", listed[0].appName)
        assertTrue(listed[0].shared)
        assertEquals("Secret", inbox.query(NotificationQuery(), NotificationSharePolicy(shareAll = true)).notifications[0].title.value)
    }

    @Test
    fun unsharedPackageFilterSetsFlag() {
        val inbox = NotificationInbox()
        inbox.posted(record("k1", "com.gmail", postedAt = 1L))
        val result = inbox.query(
            NotificationQuery(packageName = "com.gmail"),
            NotificationSharePolicy(),
        )
        assertTrue(result.packageNotShared)
        assertTrue(result.notifications.isEmpty())
        assertEquals(1, result.omittedUnsharedCount)
    }
}
