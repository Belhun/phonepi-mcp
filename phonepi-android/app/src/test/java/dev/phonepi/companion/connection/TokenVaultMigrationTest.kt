package dev.phonepi.companion.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenVaultMigrationTest {
    @Test
    fun plaintextIsKeptUnlessReadbackMatches() {
        assertTrue(TokenVaultMigration.acceptReadback("secret-token", "secret-token"))
        assertFalse(TokenVaultMigration.acceptReadback("secret-token", ""))
        assertFalse(TokenVaultMigration.acceptReadback("secret-token", "other"))
        assertFalse(TokenVaultMigration.acceptReadback("", ""))
    }
}
