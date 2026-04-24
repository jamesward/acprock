# acprock — Spec

A Kotlin server (packaged as a native CLI and a Docker image) that presents
the AWS **Bedrock Converse** and **Bedrock Mantle** (OpenAI-compatible)
APIs on localhost and proxies every call to a locally-running **Agent
Client Protocol** (ACP) agent harness.

The goal is drop-in local-dev substitution for Bedrock: production code
targets real Bedrock, dev code re-points `endpointUrl` / `base-url` at
acprock and nothing else changes — same SDKs, same model IDs, same request
and response shapes.

## Guiding principle: match Bedrock behavior

We aim to reproduce Bedrock's observable behavior as closely as possible
given that inference is actually happening inside an agent harness. The
harness's own system prompt, default tool set, and opinionated
post-processing are the primary sources of divergence from "raw"
Bedrock, so:

1. **Prefer a raw / plain-model mode** on the underlying harness whenever
   one exists. Many code assistants expose a flag or mode that strips
   the harness's default system prompt and tools, leaving something close
   to a raw model call. For any harness we support, we detect and use
   that mode by default, falling back to the harness's normal mode only
   if raw is unavailable.
2. **Document divergence** per harness in the harness routing table. If
   a harness cannot be put into raw mode, we warn on startup that
   responses will carry harness flavor.
3. **Never silently add our own system prompt** on the acprock side. The
   only system text the agent sees is what the caller supplied in the
   Bedrock/Mantle request, appended to whatever the harness insists on
   leaving in place.

## Non-goals

- Reproducing Bedrock IAM, SigV4 verification, Guardrails, Knowledge Bases,
  Bedrock Agents, or usage metering.
- Running inference ourselves. acprock is strictly a protocol bridge.
- Cross-host networking. This is a localhost dev tool.
- Byte-for-byte response parity with Bedrock. The harness is the inference
  engine; see the "Match Bedrock behavior" principle above for our
  strategy.

## Consumer experience

Running `acprock` boots a Ktor server (default port `9999`) that exposes
two path prefixes:

```
http://localhost:9999/
├── mantle/      → OpenAI-compatible (Spring AI, openai-java, openai-python, ...)
│   └── v1/chat/completions
│   └── v1/models
└── converse/    → Bedrock runtime
    └── model/{modelId}/converse
    └── model/{modelId}/converse-stream
```

Consumer config is a single URL change:

```properties
spring.ai.openai-sdk.base-url=http://localhost:9999/mantle
```

```kotlin
val bedrockRuntimeClient = BedrockRuntimeClient.fromEnvironment {
  endpointUrl = Url.parse("http://localhost:9999/converse")
}
```

## Model IDs

**Hard constraint: the user never changes model IDs when switching between
acprock and real Bedrock.** Consumer code submits the same model identifier
either way.

The Converse route accepts the full Bedrock modelId taxonomy:
- Foundation model IDs: `anthropic.claude-sonnet-4-5-20250929-v1:0`
- Regional inference profiles: `us.anthropic...`, `eu.anthropic...`, `global.anthropic...`
- Inference profile ARNs: `arn:aws:bedrock:us-east-1::inference-profile/...`
- Provisioned-throughput / custom-model ARNs (match on the underlying base model).

The Mantle route accepts whatever Mantle accepts (OpenAI-style `model`
field carrying a Bedrock model ID).

Internally we normalize: strip region prefix, unwrap ARNs to base model ID,
then look up in the harness routing table. The inbound ID is preserved in
logs and echoed back in responses where Bedrock would echo it.

Harness-level forwarding is **best-effort**. If the chosen harness (e.g.
`kiro-cli`) runs a fixed model, the request's model ID is informational —
we log a mismatch warning but do not fail. This is documented behavior,
not a bug.

Unsupported Bedrock fields (`guardrailConfig`, `performanceConfig`,
`additionalModelRequestFields`, `promptVariables`) are accepted and
ignored in M1/M2 so client code doesn't fork between local and prod.

Errors mirror the upstream shape: unknown model on Converse →
`ValidationException` with Bedrock's JSON/EventStream envelope; unknown on
Mantle → OpenAI `{"error": {"type": "invalid_request_error", ...}}`.

## ACP registry

The existing ACP agent registry is reused verbatim:

```
https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json
```

Auto-updated hourly by the registry project. acprock fetches it on first
use, caches to `~/.acprock/registry.json` with a 1h TTL, and consults it
for auto-discovery (M3).

## Build stack

Cloned from [`jamesward/acp-web-gateway`](https://github.com/jamesward/acp-web-gateway):

- Kotlin **2.3.20**, Java **25** toolchain
- Ktor **3.4.2** (server + client, CIO engine)
- [clikt](https://github.com/ajalt/clikt) for the CLI
- `com.agentclientprotocol:acp:0.18.1` (JVM; see migration note below)
- **GraalVM native-image** for the CLI (`-Os`, `--gc=epsilon`, `--no-fallback`)
- **Jib** for the Docker image (`eclipse-temurin:25-jre`, amd64+arm64)
- `gitVersion()` for versioning, `stage` task → `:app:installDist`
- `logback`, `kotlinx.serialization`

**Kotlin/Native migration.** The current ACP Kotlin SDK only publishes a
JVM target, so the CLI ships as a GraalVM native-image. Once the SDK
(or a fork) publishes a Kotlin/Native target, we plan to switch to
Kotlin/Native compilation for the CLI — dropping the JVM toolchain and
GraalVM dependency, and producing a smaller, faster-starting single-file
binary. The Ktor server and Mantle/Converse codecs are already
written against common Kotlin APIs to make this swap mechanical.

The Converse proxy structure (Ktor data classes, content negotiation,
`POST /model/{modelId}/converse`) follows
[`jamesward/awish-bedrock`](https://github.com/jamesward/awish-bedrock)
`bedrock-api/src/main/kotlin/WebApp.kt`.

## Repository layout

Single submodule for now. Refactor into multiple modules when file count
or test surface justifies it.

```
acprock/
├── build.gradle.kts            # root, plugins-only, gitVersion()
├── settings.gradle.kts         # rootProject.name = "acprock", include("app")
├── gradle/ gradlew gradlew.bat
├── app/
│   ├── build.gradle.kts        # ktor-server, clikt, acp-sdk, graalvm, jib
│   └── src/
│       ├── main/kotlin/com/jamesward/acprock/
│       │   ├── Cli.kt          # clikt entrypoint, `acprock [flags]` (server-only)
│       │   ├── Server.kt       # Ktor app, mounts /mantle (M1), /converse (M2)
│       │   ├── acp/            # thin wrapper over acp-sdk (spawn, session cache)
│       │   └── mantle/         # OpenAI chat-completions <-> ACP codec
│       │   └── converse/       # (M2) Converse <-> ACP codec with eventstream
│       │   └── registry/       # (M3) ACP registry client + model->harness routing
│       │   └── config/         # (M4) config.toml + CLI override precedence
│       └── test/kotlin/...
│           └── MantleIntegrationTest.kt   # openai-java + Spring AI against localhost
│           └── ConverseIntegrationTest.kt # (M2) BedrockRuntimeClient against localhost
├── Dockerfile / jib config     # `acprock` as PID 1
├── SPEC.md  DEV.md  README.md
```

## CLI surface

`acprock` has no subcommands. Invoking it runs the server.

```
acprock [--port 9999] [--harness-cmd kiro-cli] [--config ~/.acprock/config.toml]
```

The Docker image's entrypoint is `acprock`.

## Milestones

### M1 — Mantle over kiro-cli, one model

Scope:
- `acprock` boots Ktor on `:9999`.
- `POST /mantle/v1/chat/completions`, non-streaming **and** SSE streaming.
- Single hardcoded harness: spawn `kiro-cli` via `--harness-cmd` (default `kiro-cli`).
- Single target model: whichever Claude model kiro-cli runs through Bedrock
  that Mantle also accepts — starting pick
  `anthropic.claude-sonnet-4-5-20250929-v1:0`, confirmed at implementation
  time.
- Model ID is accepted verbatim, echoed back, and forwarded informationally.
- System prompt passes through appended to the harness default. Documented
  as a known limitation.

Out of scope for M1: tools / function calling, multi-harness routing,
system-prompt override modes, auth validation, token-usage accounting
(synthesize zero or best-effort).

Tests:
- `integrationTest` Gradle task that requires a real local `kiro-cli`
  (matches acp-web-gateway's `integrationTest` pattern).
- Uses [`com.openai:openai-java`](https://github.com/openai/openai-java)
  **and** Spring AI's OpenAI client pointed at `http://localhost:9999/mantle`.
- Covers: non-streaming chat, SSE streaming, system message, multi-turn
  conversation, unknown-model error shape.

### M2 — Add Converse

Scope:
- `POST /converse/model/{modelId}/converse`
- `POST /converse/model/{modelId}/converse-stream` with
  `application/vnd.amazon.eventstream` framing.
- Same single hardcoded harness and model as M1.
- Model ID normalization: ARN unwrapping, regional inference profile prefix
  stripping. Inbound ID echoed back unchanged.
- Implementation style follows `awish-bedrock`'s `WebApp.kt`.

Tests:
- Uses AWS Kotlin SDK `BedrockRuntimeClient.fromEnvironment { endpointUrl = ... }`
  against `http://localhost:9999/converse`.
- Covers sync Converse, streaming Converse, ARN-form model ID, regional
  inference profile ID, unknown-model `ValidationException` shape.

### M3 — ACP registry + auto-discovery

Scope:
- Fetch + cache `registry.json` (TTL 1h, cache at `~/.acprock/registry.json`).
- Auto-pick harness per request based on the normalized model ID matched
  against the harness routing table, which is keyed on Bedrock/Mantle
  model-ID patterns → harness.

Routing is **model-ID → harness**, never the inverse. Consumers never
learn harness names.

### M4 — Config file + CLI overrides

Scope:
- `~/.acprock/config.toml`:
  ```toml
  [[model]]
  pattern = "anthropic.claude-*"
  harness = "kiro"

  [harness.kiro]
  command = "kiro-cli"
  args    = ["--acp"]
  ```
- CLI flags: `--harness <name>`, `--harness-cmd <cmd>`,
  `--model-map <pattern>=<harness>`.
- Precedence (highest first): CLI flag → config file → auto-discovery (M3).

## Explicitly deferred past M4

Tracked as open items, not part of this spec:
- Tool calling / MCP bridging (both Bedrock Converse `toolConfig` and
  OpenAI `tools`). Likely approach: ship an in-process MCP server
  (`acprock-tool-bridge`) the harness connects to, exposing exactly the
  tools declared in the current request.
- System-prompt override modes beyond "append".
- Token-usage synthesis (tokenize locally when the harness doesn't report).
- Multi-turn session caching across requests (reuse an ACP session when
  the next request's prefix matches what the harness already has).
- Library-embed adapters (`AcprockBridge.asBedrockRuntimeClient()`, etc.)
  for in-process test use without the HTTP hop.
- `/v1/models` enumeration from the registry.

## Risks to validate early

- **ACP coverage in kiro-cli**: prompt + streamed response round-trip must
  work via `acp-sdk:0.18.1`. Validate in M1 before committing to the
  codec shape.
- **System-prompt bleed-through**: kiro-cli's default system prompt will
  affect outputs. Document in M1; revisit in the deferred list.
- **Mantle model-ID ↔ Bedrock model-ID overlap**: pick an M1 model that is
  accepted by both the Mantle OpenAI-compatible endpoint *and* runs on
  kiro-cli. Claude Sonnet 4.5 family is the starting assumption.
- **Unusable registry entries**: not every agent in the ACP registry will
  run in every environment (auth, network, license). Discovery must
  distinguish "listed" from "launchable here".
