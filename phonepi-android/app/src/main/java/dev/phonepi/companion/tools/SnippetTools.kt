package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.data.SnippetEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class AddSnippetTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "add_snippet"

    override suspend fun handle(params: JsonObject): JsonObject {
        val title = params.requireString("title") ?: return toolError("title is required")
        val content = params.requireString("content") ?: return toolError("content is required")
        val type = params.requireString("type") ?: return toolError("type is required")
        if (type !in SNIPPET_TYPES) return toolError("Invalid snippet type: $type")
        val now = System.currentTimeMillis()
        val id = dao.insert(
            SnippetEntity(
                title = title,
                content = content,
                type = type,
                tags = normalizeTags(params.optionalString("tags")),
                createdAt = now,
                updatedAt = now,
            ),
        )
        val snippet = dao.getById(id) ?: return toolError("Failed to create snippet")
        return success { put("snippet", snippet.toJson()) }
    }
}

class GetAllSnippetsTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "get_all_snippets"

    override suspend fun handle(params: JsonObject): JsonObject {
        val items = dao.getAll()
        return success {
            put("snippets", snippetsArray(items))
            put("count", items.size)
        }
    }
}

class GetSnippetByIdTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "get_snippet_by_id"

    override suspend fun handle(params: JsonObject): JsonObject {
        val id = params.requireInt("id")?.toLong()
            ?: return toolError("Missing required parameter: id")
        val snippet = dao.getById(id)
            ?: return toolError("Snippet not found: id=$id")
        return success { put("snippet", snippet.toJson()) }
    }
}

class GetSnippetsByTypeTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "get_snippets_by_type"

    override suspend fun handle(params: JsonObject): JsonObject {
        val type = params.requireString("type") ?: return toolError("type is required")
        if (type !in SNIPPET_TYPES) return toolError("Invalid snippet type: $type")
        val items = dao.getByType(type)
        return success {
            put("type", type)
            put("snippets", snippetsArray(items))
            put("count", items.size)
        }
    }
}

class SearchSnippetsTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "search_snippets"

    override suspend fun handle(params: JsonObject): JsonObject {
        val query = params.requireString("query") ?: return toolError("query is required")
        val items = dao.search(query)
        return success {
            put("query", query)
            put("snippets", snippetsArray(items))
            put("count", items.size)
        }
    }
}

class UpdateSnippetTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "update_snippet"

    override suspend fun handle(params: JsonObject): JsonObject {
        val id = params.requireInt("id")?.toLong()
            ?: return toolError("Missing required parameter: id")
        val existing = dao.getById(id) ?: return toolError("Snippet not found: id=$id")
        val title = params.requireString("title") ?: return toolError("title is required")
        val content = params.requireString("content") ?: return toolError("content is required")
        val type = params.requireString("type") ?: return toolError("type is required")
        if (type !in SNIPPET_TYPES) return toolError("Invalid snippet type: $type")
        val updated = existing.copy(
            title = title,
            content = content,
            type = type,
            tags = normalizeTags(params.optionalString("tags")),
            updatedAt = System.currentTimeMillis(),
        )
        dao.update(updated)
        return success { put("snippet", updated.toJson()) }
    }
}

class DeleteSnippetTool(context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()
    override val name = "delete_snippet"

    override suspend fun handle(params: JsonObject): JsonObject {
        val id = params.requireInt("id")?.toLong()
            ?: return toolError("Missing required parameter: id")
        val deleted = dao.deleteById(id) > 0
        return if (deleted) {
            success {
                put("deleted", true)
                put("id", id)
            }
        } else {
            toolError("Snippet not found: id=$id")
        }
    }
}
