package com.jamesward.acprock

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

data class HarnessSpec(
    val command: String,
    val args: List<String>,
)

/**
 * Spawns a single ACP harness subprocess and lends it out one prompt at a time.
 *
 * M1 uses a single long-lived session; the bridge serializes prompts onto it.
 * Session caching across conversations and multi-session support come later.
 */
class AcpBridge(
    private val spec: HarnessSpec,
    private val cwd: String = System.getProperty("user.dir"),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val promptMutex = Mutex()

    @Volatile private var process: Process? = null
    @Volatile private var protocol: Protocol? = null
    @Volatile private var client: Client? = null
    @Volatile private var session: com.agentclientprotocol.client.ClientSession? = null
    @Volatile private var sessionId: SessionId? = null
    private val startMutex = Mutex()

    private suspend fun ensureStarted() = startMutex.withLock {
        if (session != null) return@withLock

        val cmd = buildList {
            add(spec.command)
            addAll(spec.args)
        }
        log.info("Spawning harness: {}", cmd.joinToString(" "))
        val p = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process = p

        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = p.inputStream.asSource().buffered(),
            output = p.outputStream.asSink().buffered(),
            name = "acprock-${spec.command}",
        )
        val proto = Protocol(scope, transport)
        protocol = proto
        val c = Client(proto)
        client = c

        proto.start()
        val agentInfo = c.initialize(ClientInfo())
        log.info("ACP agent initialized: {}", agentInfo.implementation)

        val s = c.newSession(
            SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
            AutoApproveClientOperationsFactory,
        )
        session = s
        sessionId = s.sessionId
        log.info("ACP session created: {}", s.sessionId)
    }

    /**
     * Send the user's text and stream back the agent's text chunks.
     * Tool calls, plans, thoughts are ignored in M1.
     */
    suspend fun prompt(userText: String): Flow<String> = flow {
        ensureStarted()
        val s = session ?: error("session not started")
        promptMutex.withLock {
            s.prompt(listOf(ContentBlock.Text(userText))).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> when (val u = event.update) {
                        is SessionUpdate.AgentMessageChunk -> {
                            val block = u.content
                            if (block is ContentBlock.Text) emit(block.text)
                        }
                        else -> {} // M1: ignore thoughts, tool calls, plans, etc.
                    }
                    is Event.PromptResponseEvent -> {} // stream ends when Flow completes
                }
            }
        }
    }

    suspend fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {} // best-effort
        try {
            protocol?.close()
        } catch (_: Throwable) {}
        process?.destroy()
        scope.coroutineContext[Job]?.cancel()
    }
}

private object AutoApproveClientOperationsFactory : ClientOperationsFactory {
    override suspend fun createClientOperations(
        sessionId: SessionId,
        sessionResponse: AcpCreatedSessionResponse,
    ): ClientSessionOperations = AutoApproveClientOps
}

private object AutoApproveClientOps : ClientSessionOperations {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val pick = permissions.firstOrNull {
            it.kind == PermissionOptionKind.ALLOW_ONCE || it.kind == PermissionOptionKind.ALLOW_ALWAYS
        } ?: permissions.first()
        return RequestPermissionResponse(outcome = RequestPermissionOutcome.Selected(pick.optionId))
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        log.debug("out-of-turn notify: {}", notification)
    }
}
