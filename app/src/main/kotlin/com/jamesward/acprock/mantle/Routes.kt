package com.jamesward.acprock.mantle

import com.jamesward.acprock.AcpBridge
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false; explicitNulls = false }

fun Route.mantleRoutes(bridge: AcpBridge) {
    route("/mantle") {
        post("/v1/chat/completions") {
            val body = call.receive<ChatCompletionRequest>()
            val userText = foldMessagesToPrompt(body.messages)

            if (body.stream == true) {
                // The SSE plugin path is installed separately below; fall through:
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Set Accept: text/event-stream and POST to /mantle/v1/chat/completions for streaming"))
                return@post
            }

            val text = bridge.prompt(userText).fold(StringBuilder()) { acc, chunk -> acc.append(chunk) }.toString()
            val now = System.currentTimeMillis() / 1000
            call.respond(
                ChatCompletionResponse(
                    id = "chatcmpl-${java.util.UUID.randomUUID()}",
                    created = now,
                    model = body.model,
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = text),
                            finishReason = "stop",
                        )
                    ),
                    usage = Usage(), // M1: token accounting deferred
                )
            )
        }

        sse("/v1/chat/completions-stream") {
            // Dedicated SSE endpoint for clients that can choose it explicitly.
            // The main endpoint will be extended to handle stream=true with SSE once we unify.
            val req = call.receive<ChatCompletionRequest>()
            val id = "chatcmpl-${java.util.UUID.randomUUID()}"
            val now = System.currentTimeMillis() / 1000
            val chunks = bridge.prompt(foldMessagesToPrompt(req.messages)).toList()
            for ((i, text) in chunks.withIndex()) {
                val chunk = ChatCompletionChunk(
                    id = id,
                    created = now,
                    model = req.model,
                    choices = listOf(ChunkChoice(0, Delta(role = if (i == 0) "assistant" else null, content = text))),
                )
                send(ServerSentEvent(data = json.encodeToString(chunk)))
            }
            val finalChunk = ChatCompletionChunk(
                id = id,
                created = now,
                model = req.model,
                choices = listOf(ChunkChoice(0, Delta(), finishReason = "stop")),
            )
            send(ServerSentEvent(data = json.encodeToString(finalChunk)))
            send(ServerSentEvent(data = "[DONE]"))
        }
    }
}

private fun foldMessagesToPrompt(messages: List<ChatMessage>): String {
    // M1: flatten into a single text prompt. System prompts get appended as a prefix;
    // the harness's own system prompt still applies underneath.
    val sb = StringBuilder()
    for (m in messages) {
        when (m.role) {
            "system" -> sb.append("[system]\n").append(m.content.orEmpty()).append("\n\n")
            "user" -> sb.append(m.content.orEmpty()).append("\n")
            "assistant" -> sb.append("[assistant]\n").append(m.content.orEmpty()).append("\n\n")
            else -> sb.append(m.content.orEmpty()).append("\n")
        }
    }
    return sb.toString().trimEnd()
}
