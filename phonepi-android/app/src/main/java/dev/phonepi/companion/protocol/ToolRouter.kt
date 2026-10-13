package dev.phonepi.companion.protocol

import dev.phonepi.companion.commands.CommandGate
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.tools.ToolHandler
import dev.phonepi.companion.tools.toolError

/**
 * Maps a tool name to one handler. Any handler failure becomes an error payload,
 * never a crash, so a single tool bug cannot kill the socket.
 */
class ToolRouter(handlers: List<ToolHandler>) {

    private val handlers = handlers.associateBy { it.name }

    suspend fun dispatch(request: WsMessage.Request): ToolResponse {
        val gated = CommandGate.check(request.tool)
        if (gated != null) {
            val response = ToolResponse(request.requestId, toolError(gated))
            ActivityLog.logTool(request.tool, request.requestId, response.data)
            return response
        }
        val handler = handlers[request.tool]
        if (handler == null) {
            val response = ToolResponse.error(request.requestId, "Unknown tool: ${request.tool}")
            ActivityLog.logTool(request.tool, request.requestId, response.data)
            return response
        }
        val response = try {
            val data = handler.handle(request.params)
            ToolResponse(request.requestId, data)
        } catch (e: SecurityException) {
            ToolResponse.error(request.requestId, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolResponse.error(request.requestId, e.message ?: "Tool failed")
        }
        ActivityLog.logTool(request.tool, request.requestId, response.data)
        return response
    }
}
