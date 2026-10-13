package dev.phonepi.companion.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationQueryTest {
    @Test
    fun parsesClassicQParam() {
        val plan = NavigationQuery.fromMapsUrl("https://maps.google.com/?q=1600+Amphitheatre+Parkway")
        assertNotNull(plan)
        assertEquals("1600 Amphitheatre Parkway", plan!!.query)
    }

    @Test
    fun parsesDirectionsDestination() {
        val plan = NavigationQuery.fromMapsUrl(
            "https://www.google.com/maps/dir/?api=1&destination=Costco+Plano",
        )
        assertEquals("Costco Plano", plan!!.query)
    }

    @Test
    fun parsesPlacePathAndAtCoordinates() {
        val plan = NavigationQuery.fromMapsUrl(
            "https://www.google.com/maps/place/Home+Depot/@33.0198,-96.6989,17z",
        )
        assertNotNull(plan)
        assertEquals("33.0198,-96.6989", plan!!.query)
        assertEquals("Home Depot", plan.display)
    }

    @Test
    fun rejectsNonMapsHosts() {
        assertFalse(MapsLink.isAllowed("https://evil.example/maps?q=home"))
        assertNull(NavigationQuery.fromMapsUrl("https://evil.example/?q=home"))
    }

    @Test
    fun allowsGoogGlMapsShortLink() {
        assertTrue(MapsLink.isAllowed("https://maps.app.goo.gl/abc123"))
        val plan = NavigationQuery.fromMapsUrl("https://maps.app.goo.gl/abc123")
        assertEquals("https://maps.app.goo.gl/abc123", plan!!.query)
        assertEquals("https://maps.app.goo.gl/abc123", plan.sourceUrl)
    }

    @Test
    fun fromAddressRejectsBlank() {
        assertNull(NavigationQuery.fromAddress("  "))
    }

    @Test
    fun fromCoordinatesValidatesRange() {
        assertNull(NavigationQuery.fromCoordinates(91.0, 0.0))
        assertEquals("30.5,-97.7", NavigationQuery.fromCoordinates(30.5, -97.7)!!.query)
    }
}

class NavigationUrisTest {
    @Test
    fun primaryUriIsGoogleNavigation() {
        val plan = NavigationPlan(query = "home depot plano", display = "Home Depot")
        val uris = NavigationUris.candidates(plan)
        assertTrue(uris[0].startsWith("google.navigation:q="))
        assertTrue(uris[0].contains("home%20depot%20plano"))
        assertTrue(uris[1].startsWith("geo:0,0?q="))
        assertTrue(uris.last().startsWith("https://www.google.com/maps/dir/"))
        assertFalse(uris.any { it.startsWith("intent:") })
    }

    @Test
    fun includesAllowlistedSourceUrl() {
        val url = "https://maps.app.goo.gl/abc"
        val plan = NavigationPlan(query = url, display = url, sourceUrl = url)
        val uris = NavigationUris.candidates(plan)
        assertTrue(uris.contains(url))
    }

    @Test
    fun dropsNonMapsSourceUrl() {
        val plan = NavigationPlan(
            query = "Austin",
            display = "Austin",
            sourceUrl = "https://evil.example/open",
        )
        val uris = NavigationUris.candidates(plan)
        assertFalse(uris.contains("https://evil.example/open"))
    }
}

class SavedLocationRulesTest {
    @Test
    fun requiresDestination() {
        val err = SavedLocationRules.validate(
            SavedLocationDraft("home", null, null, null, null),
        )
        assertTrue(err is LocationValidation.Err)
    }

    @Test
    fun acceptsAddress() {
        val ok = SavedLocationRules.validate(
            SavedLocationDraft("Home", "123 Main St", null, null, null),
        ) as LocationValidation.Ok
        assertEquals("home", ok.record.nameKey)
        assertEquals("Home", ok.record.name)
        assertEquals("123 Main St", SavedLocationRules.navigationQuery(ok.record))
    }

    @Test
    fun prefersCoordinatesForNavigation() {
        val ok = SavedLocationRules.validate(
            SavedLocationDraft("work", "ignored", 33.0, -96.7, null),
        ) as LocationValidation.Ok
        assertEquals("33.0,-96.7", SavedLocationRules.navigationQuery(ok.record))
    }

    @Test
    fun rejectsNonMapsUrl() {
        val err = SavedLocationRules.validate(
            SavedLocationDraft("x", null, null, null, "https://example.com"),
        )
        assertTrue(err is LocationValidation.Err)
    }

    @Test
    fun nameKeyIsCaseInsensitive() {
        assertEquals("costco", SavedLocationRules.nameKey(" Costco "))
    }
}
