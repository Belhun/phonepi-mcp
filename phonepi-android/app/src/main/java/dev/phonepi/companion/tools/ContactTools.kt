package dev.phonepi.companion.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.phonepi.companion.contacts.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private fun Context.hasContactsPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

class GetContactsTool(private val context: Context) : ToolHandler {
    private val repo = ContactRepository(context)
    override val name = "get_contacts"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!context.hasContactsPermission()) {
            return toolError("READ_CONTACTS permission not granted")
        }
        val contacts = withContext(Dispatchers.IO) { repo.getAll() }
        return success {
            put("contacts", buildJsonArray { contacts.forEach { add(it) } })
            put("count", contacts.size)
        }
    }
}

class SearchContactsTool(private val context: Context) : ToolHandler {
    private val repo = ContactRepository(context)
    override val name = "search_contacts"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!context.hasContactsPermission()) {
            return toolError("READ_CONTACTS permission not granted")
        }
        val query = params.requireString("query") ?: return toolError("Missing required parameter: query")
        val limit = (params.requireInt("limit") ?: 10).coerceIn(1, 50)
        val contacts = withContext(Dispatchers.IO) { repo.search(query, limit) }
        return success {
            put("contacts", buildJsonArray { contacts.forEach { add(it) } })
            put("count", contacts.size)
            put("query", query)
        }
    }
}

class GetContactByIdTool(private val context: Context) : ToolHandler {
    private val repo = ContactRepository(context)
    override val name = "get_contact_by_id"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!context.hasContactsPermission()) {
            return toolError("READ_CONTACTS permission not granted")
        }
        val id = params.requireString("id") ?: return toolError("Missing required parameter: id")
        val contact = withContext(Dispatchers.IO) { repo.getById(id) } ?: return toolError("Contact not found: $id")
        return success { put("contact", contact) }
    }
}

class AddContactTool(private val context: Context) : ToolHandler {
    private val repo = ContactRepository(context)
    override val name = "add_contact"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!context.hasContactsPermission()) {
            return toolError("WRITE_CONTACTS permission not granted")
        }
        val contact = params.requireObject("contact") ?: return toolError("contact is required")
        val created = withContext(Dispatchers.IO) { repo.add(contact) }
        return success { put("contact", created) }
    }
}

class UpdateContactTool(private val context: Context) : ToolHandler {
    private val repo = ContactRepository(context)
    override val name = "update_contact"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!context.hasContactsPermission()) {
            return toolError("WRITE_CONTACTS permission not granted")
        }
        val contact = params.requireObject("contact") ?: return toolError("contact is required")
        val updated = withContext(Dispatchers.IO) { repo.update(contact) }
        return success { put("contact", updated) }
    }
}

class DeleteContactTool(private val context: Context) : ToolHandler {
    private val repo = ContactRepository(context)
    override val name = "delete_contact"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!context.hasContactsPermission()) {
            return toolError("WRITE_CONTACTS permission not granted")
        }
        val id = params.requireString("id") ?: return toolError("Missing required parameter: id")
        val deleted = withContext(Dispatchers.IO) { repo.delete(id) }
        return if (deleted) {
            success {
                put("deleted", true)
                put("id", id)
            }
        } else {
            toolError("Contact not found: $id")
        }
    }
}
