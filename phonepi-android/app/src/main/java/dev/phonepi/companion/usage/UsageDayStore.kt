package dev.phonepi.companion.usage

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily rollups on the phone. One JSON file per local date under [dir].
 * Events are only kept a few days by Android, so completed days stay here.
 */
class UsageDayStore(
    private val dir: File,
    private val retentionDays: Int = USAGE_RETENTION_DAYS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun readAll(): List<UsageDay> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { DATE_FILE.matches(it.name) }
            .mapNotNull { file -> parseUsageDay(file.readText()) }
            .sortedBy { it.date }
    }

    fun read(date: String): UsageDay? {
        val file = File(dir, "$date.json")
        if (!file.isFile) return null
        return parseUsageDay(file.readText())
    }

    fun write(days: List<UsageDay>) {
        dir.mkdirs()
        for (day in days) {
            val file = File(dir, "${day.date}.json")
            file.writeText(usageDayJson(day))
        }
        prune()
    }

    fun replaceAll(days: List<UsageDay>) {
        dir.mkdirs()
        clear()
        write(days)
    }

    fun clear() {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (DATE_FILE.matches(file.name)) file.delete()
        }
    }

    fun prune() {
        if (!dir.isDirectory) return
        val cutoff = Instant.ofEpochMilli(now())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .minusDays(retentionDays.toLong())
        dir.listFiles()?.forEach { file ->
            val match = DATE_FILE.matchEntire(file.name) ?: return@forEach
            val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
                ?: return@forEach
            if (date.isBefore(cutoff)) file.delete()
        }
    }

    companion object {
        private val DATE_FILE = Regex("""^(\d{4}-\d{2}-\d{2})\.json$""")
    }
}
