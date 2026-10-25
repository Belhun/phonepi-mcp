package dev.phonepi.companion.connection

object TokenVaultMigration {
    fun acceptReadback(expected: String, readback: String): Boolean =
        expected.isNotBlank() && readback == expected
}
