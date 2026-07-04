package dev.phonepi.companion.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ContactRepository(private val context: Context) {

    data class PhoneLookupResult(
        val contactId: String,
        val displayName: String,
    )

    /** Resolve which contact owns an SMS sender address (may differ from the number on the contact card). */
    fun lookupByPhone(phoneNumber: String): PhoneLookupResult? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.CONTACT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val id = cursor.getString(idIdx) ?: return null
            val name = cursor.getString(nameIdx) ?: ""
            return PhoneLookupResult(contactId = id, displayName = name)
        }
        return null
    }

    fun getAll(): List<JsonObject> {
        val resolver = context.contentResolver
        val results = mutableListOf<JsonObject>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                results += loadContact(id, cursor.getString(nameIdx) ?: "")
            }
        }
        return results
    }

    fun search(query: String, limit: Int = 10): List<JsonObject> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()

        val results = mutableListOf<JsonObject>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext() && results.size < limit) {
                val name = cursor.getString(nameIdx) ?: ""
                val nameLower = name.lowercase()
                if (terms.all { term -> nameLower.contains(term) }) {
                    val id = cursor.getString(idIdx) ?: continue
                    results += loadContact(id, name)
                }
            }
        }
        return results
    }

    fun getById(id: String): JsonObject? {
        val resolver = context.contentResolver
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID}=?",
            arrayOf(id),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            return loadContact(id, cursor.getString(nameIdx) ?: "")
        }
        return null
    }

    private fun loadContact(id: String, name: String): JsonObject {
        val phones = mutableListOf<JsonObject>()
        val emails = mutableListOf<JsonObject>()
        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.MIMETYPE, Phone.NUMBER, Phone.TYPE, Email.ADDRESS, Email.TYPE),
            "${Data.CONTACT_ID}=?",
            arrayOf(id),
            null,
        )?.use { cursor ->
            val mimeIdx = cursor.getColumnIndexOrThrow(Data.MIMETYPE)
            val dataIdIdx = cursor.getColumnIndexOrThrow(Data._ID)
            val phoneIdx = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val phoneTypeIdx = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val emailIdx = cursor.getColumnIndexOrThrow(Email.ADDRESS)
            val emailTypeIdx = cursor.getColumnIndexOrThrow(Email.TYPE)
            while (cursor.moveToNext()) {
                when (cursor.getString(mimeIdx)) {
                    Phone.CONTENT_ITEM_TYPE -> phones += buildJsonObject {
                        put("id", cursor.getString(dataIdIdx))
                        put("label", phoneTypeLabel(cursor.getInt(phoneTypeIdx)))
                        put("number", cursor.getString(phoneIdx) ?: "")
                    }
                    Email.CONTENT_ITEM_TYPE -> emails += buildJsonObject {
                        put("id", cursor.getString(dataIdIdx))
                        put("label", emailTypeLabel(cursor.getInt(emailTypeIdx)))
                        put("email", cursor.getString(emailIdx) ?: "")
                    }
                }
            }
        }
        return buildJsonObject {
            put("id", id)
            put("name", name)
            put("phoneNumbers", buildJsonArray { phones.forEach { add(it) } })
            put("emails", buildJsonArray { emails.forEach { add(it) } })
            put("addresses", buildJsonArray { })
        }
    }

    fun add(contact: JsonObject): JsonObject {
        val name = contact.requireString("name") ?: error("name is required")
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null)
            .withValue(RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, name)
            .build()

        contact.array("phoneNumbers").forEach { phone ->
            val obj = phone.jsonObject
            val number = obj.requireString("number") ?: return@forEach
            val label = obj.optionalString("label") ?: "mobile"
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, number)
                .withValue(Phone.TYPE, phoneLabelType(label))
                .build()
        }

        contact.array("emails").forEach { email ->
            val obj = email.jsonObject
            val address = obj.requireString("email") ?: return@forEach
            val label = obj.optionalString("label") ?: "work"
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, address)
                .withValue(Email.TYPE, emailLabelType(label))
                .build()
        }

        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = results.firstOrNull()?.uri?.lastPathSegment
        val contactId = rawId?.let { rawContactIdToContactId(it) }
        return contactId?.let { getById(it) } ?: buildJsonObject {
            put("id", "unknown")
            put("name", name)
        }
    }

    fun update(contact: JsonObject): JsonObject {
        val id = contact.requireString("id") ?: error("id is required")
        val name = contact.requireString("name") ?: error("name is required")
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newUpdate(ContactsContract.Contacts.CONTENT_URI)
            .withSelection("${ContactsContract.Contacts._ID}=?", arrayOf(id))
            .withValue(ContactsContract.Contacts.DISPLAY_NAME, name)
            .build()
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return getById(id) ?: buildJsonObject {
            put("id", id)
            put("name", name)
        }
    }

    fun delete(id: String): Boolean {
        val deleted = context.contentResolver.delete(
            ContactsContract.Contacts.CONTENT_URI,
            "${ContactsContract.Contacts._ID}=?",
            arrayOf(id),
        )
        return deleted > 0
    }

    private fun rawContactIdToContactId(rawContactId: String): String? {
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID),
            "${RawContacts._ID}=?",
            arrayOf(rawContactId),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndexOrThrow(RawContacts.CONTACT_ID)
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun phoneTypeLabel(type: Int): String = when (type) {
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_MOBILE -> "mobile"
        else -> "other"
    }

    private fun emailTypeLabel(type: Int): String = when (type) {
        Email.TYPE_HOME -> "home"
        Email.TYPE_WORK -> "work"
        else -> "other"
    }

    private fun phoneLabelType(label: String): Int = when (label.lowercase()) {
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        else -> Phone.TYPE_MOBILE
    }

    private fun emailLabelType(label: String): Int = when (label.lowercase()) {
        "home" -> Email.TYPE_HOME
        else -> Email.TYPE_WORK
    }

    private fun JsonObject.requireString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.array(key: String): JsonArray =
        this[key] as? JsonArray ?: buildJsonArray { }
}
