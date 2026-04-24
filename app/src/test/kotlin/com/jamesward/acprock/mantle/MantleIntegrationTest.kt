package com.jamesward.acprock.mantle

import com.jamesward.acprock.AcpBridge
import com.jamesward.acprock.HarnessSpec
import com.jamesward.acprock.acprockModule
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end test against a locally-running ACP harness (defaults to kiro-cli).
 * Skipped by the default `test` task; run via `./gradlew integrationTest`.
 */
class MantleIntegrationTest {
    @Test
    fun `openai java client round-trip via kiro-cli`() = runBlocking {
        val harnessCmd = System.getProperty("acprock.harness.cmd", "kiro-cli")
        val bridge = AcpBridge(HarnessSpec(command = harnessCmd, args = listOf("acp")))

        val server = embeddedServer(CIO, port = 0) { acprockModule(bridge) }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = OpenAIOkHttpClient.builder()
                .baseUrl("http://localhost:$port/mantle")
                .apiKey("dev")
                .build()

            val params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of("anthropic.claude-sonnet-4-5-20250929-v1:0"))
                .addUserMessage("Say only the single word: pong")
                .build()

            val completion = client.chat().completions().create(params)
            val text = completion.choices().firstOrNull()?.message()?.content()?.orElse("") ?: ""
            assertTrue(text.isNotBlank(), "expected non-empty assistant content")
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            bridge.close()
        }
    }
}
