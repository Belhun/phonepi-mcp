package dev.phonepi.companion.alarm

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One alarm PhonePi asked the Clock app to create. Not a full Clock inventory. */
@Serializable
data class PhonePiAlarmRecord(
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val days: List<Int> = emptyList(),
    val createdAt: Long,
)

object AlarmRecordLog {
    const val MAX_RECORDS = 40

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PhonePiAlarmRecord.serializer())

    fun remember(
        existing: List<PhonePiAlarmRecord>,
        next: PhonePiAlarmRecord,
        max: Int = MAX_RECORDS,
    ): List<PhonePiAlarmRecord> = (listOf(next) + existing).take(max)

    fun encode(records: List<PhonePiAlarmRecord>): String = json.encodeToString(serializer, records)

    fun decode(raw: String?): List<PhonePiAlarmRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
