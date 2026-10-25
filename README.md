# Botati (بوتاتي) — Android multi-bot agent app

An Android app that hosts multiple user-created AI bots. Each bot has its own
system prompt, model, tool set and MCP servers; all bots share **one API key
per provider** (a key dedicated to this app — never your Nabd key), stored in
an encrypted vault. Bots are governed by a strict **Policy Engine**: in chat
they ask before anything irreversible, and under an explicitly assigned task
they may act freely **only inside the approved Scope Contract**.

Spec: `../MVP_SPEC.md` (v1.4 — §18–29 amendments supersede earlier sections).
Build diary, evidence and deviations: `BUILD_LOG.md`.

## Architecture

```
com.botati
├── domain/            Pure Kotlin core (no Android dependencies, fully unit-tested)
│   ├── policy/        Two-axis risk (Effect E0–E3 × Disclosure D0–D2), ScopeContract
│   │                  (closed enums, contractHash), PolicyDecision (Allow/Ask/Deny/
│   │                  Pause/Stop) and PolicyEngine.decide() — a pure function
│   ├── tools/         Tool registry + classifications, deterministic idempotency keys,
│   │                  SAF file-grant guard
│   ├── agent/         AgentEngine loop, SSE tool-call delta assembler, usage accounting
│   │                  (costSource actual|estimated|unknown), context truncation,
│   │                  system-prompt envelope builder
│   ├── contracts/     Discovery-scope target resolver
│   └── security/      SHA-256, secret redactor
├── data/              Room DB (10 tables — NO secrets in Room), Tink-AEAD Vault
│                      (Android Keystore master key, ciphertext in DataStore,
│                      vault file excluded from cloud backup), repositories
├── infrastructure/    OpenRouter client (Ktor CIO + SSE, release builds log nothing,
│                      debug logs redacted headers only), contract proposer
│                      (structured outputs + local validation + exactly one retry),
│                      MCP client behind McpClientPort, built-in tools, ToolRouter
│                      (enforces the SAF guard + injects idempotency keys executor-side)
├── service/           Task foreground service (dataSync) with onTimeout()
├── di/                Koin module
└── feature/           Compose screens: bots, bot editor, chat (streaming, tool-call
                       cards, approval cards, contract approval), MCP servers,
                       vault (FLAG_SECURE), audit/settings (ceilings, export)
```

### Safety invariants (enforced in code, covered by tests)
- `PolicyEngine.decide()` precedence: **Deny → Stop (ceilings/expiry) → Pause
  (out of contract) → chat Ask/Allow tiers → Allow**. Five distinct decisions,
  each audit-logged with a `reasonCode`.
- Email `send_reply` model schema contains **threadId + body only** — no
  recipients, no idempotency key. The deterministic key
  `sha256(taskId+threadId+bodyHash)` is generated in the executor layer.
- Unknown MCP tools default to **E3/D2** until the user classifies them.
- Missing `usage.cost` is **never** silently 0: it is estimated from a price
  table or recorded `unknown` (tasks stop after 3 consecutive unknowns).
- SSE tool-call arguments are assembled fully per index and JSON-validated
  before any execution; an unparseable call is never executed.
- `tool_not_found` is a structured result that counts as a step; 3 in a row
  stops the loop.

### MCP compatibility (dual-era, spec §21)
`McpClientPort` isolates the transport. The shipped client
(`MinimalMcpClient`) speaks the legacy session era (2025-03-26:
`initialize` → `Mcp-Session-Id` header → `notifications/initialized`, with a
per-server `Mutex` so legacy calls never interleave, and disconnect-based
cancellation — `notifications/cancelled` has no ACK) and probes the stateless
era (2026-07-28: `server/discover`, no handshake, `_meta` on every request).
Tools are exposed to models as `<sanitizedPrefix>__<tool>`; prefixes never
contain `__` themselves.

> Deviation: the official MCP Kotlin SDK (0.15.0) targets a much newer Kotlin
> than the pinned AGP 8.7.3 / Kotlin 2.0.21 toolchain can consume, so the
> client is a minimal Streamable-HTTP JSON-RPC implementation behind the same
> port interface — exactly the isolation §21 prescribes.

## Building

### Normal path (Gradle)
```
./gradlew testDebugUnitTest   # 79 unit tests (75 standalone + 4 MockEngine)
./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
```
The wrapper is pinned to Gradle 8.10.2 (run `gradle wrapper --gradle-version
8.10.2` once if `gradle/wrapper/gradle-wrapper.jar` is absent). Point
`local.properties` at your SDK (`sdk.dir=…`, platform android-35 +
build-tools 35.0.0).

> **Note for this VM:** Gradle cannot run inside the Muse VM — its
> client↔daemon loopback TCP is denied by the VM's `other_tcp` network
> permission (see BUILD_LOG.md). On a normal machine (or with that permission
> set to Ask/Allow) the Gradle build above is the supported path. JVMs behind
> the VM proxy additionally need `systemProp.https.proxyHost/Port` in
> `gradle.properties`.

### What was verified in the VM tonight
- All 79 unit tests green: 75 via standalone kotlinc 2.0.21 + JUnit console, 4 MockEngine (Gradle-only, verified locally with a stub BuildConfig)
  (same sources Gradle runs). Evidence in `BUILD_LOG.md`.
- Full app compiled and packaged with the manual pipeline (aapt2 + kotlinc +
  d8 + apksigner; Room's generated classes hand-written in `manual-room-impl/`
  because KSP/kapt cannot run in this VM) — see `BUILD_LOG.md` for the APK
  path and hash.

## First run
1. Open **الخزنة** and paste a dedicated OpenRouter API key (test button
   included). Optionally add a Tavily-compatible search key + endpoint.
2. Three preset bots are seeded on first launch: **مساعد البريد**, **الباحث**,
   **محلّل الملفات** (§13 templates).
3. Add MCP servers under **خوادم MCP**, classify each tool's E/D levels,
   then link servers and tools per bot in the bot editor.
4. In chat: normal messages run in supervised chat mode (approval cards);
   **🎯 تكليف بمهمة** proposes a Scope Contract — your approval tap is the
   authorization, valid only inside that contract.

## Not in this build (by design)
- Phase 4: the Gmail MCP server and fully autonomous mail tasks. It needs
  your interactive Google OAuth consent, so it is intentionally left for a
  session with you present. The app side (contract approval, policy, audit,
  foreground service) is already in place; a mail MCP server plugs into the
  existing server screen.
