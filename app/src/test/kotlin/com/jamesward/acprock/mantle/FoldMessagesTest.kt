package com.jamesward.acprock.mantle

import kotlin.test.Test
import kotlin.test.assertEquals

class FoldMessagesTest {
    // Smokes the mantle request shape decodes as expected without ever touching ACP.
    @Test
    fun `decodes minimal chat completion request`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val req = json.decodeFromString<ChatCompletionRequest>(
            """{"model":"anthropic.claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}"""
        )
        assertEquals("anthropic.claude-sonnet-4-5", req.model)
        assertEquals(1, req.messages.size)
        assertEquals("user", req.messages[0].role)
        assertEquals("hi", req.messages[0].content)
    }

    @Test
    fun `unknown top-level fields are ignored`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val req = json.decodeFromString<ChatCompletionRequest>(
            """{"model":"m","messages":[],"guardrailConfig":{"foo":"bar"},"performanceConfig":{}}"""
        )
        assertEquals("m", req.model)
    }
}
