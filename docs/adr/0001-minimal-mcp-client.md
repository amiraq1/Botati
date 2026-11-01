# ADR 0001 — A hand-written MCP client instead of the official Kotlin SDK

- **Status:** accepted (re-affirmed 2026-11-01)
- **Supersedes:** nothing
- **Related:** spec §21, finding BOT-15 in `docs/CODE_REVIEW_2026-11-01.md`
- **Implementation:** `infrastructure/mcp/MinimalMcpClient.kt`

## Context

Botati must speak the Model Context Protocol to arbitrary user-supplied servers.
Two protocol eras exist in the wild and a client has to cope with both:

| Era | Handshake | Session | Discovery | Self-description |
|---|---|---|---|---|
| legacy (≤ 2025-11-25) | `initialize` → `notifications/initialized` | `Mcp-Session-Id` header | `tools/list` | none |
| stateless (2026-07-28) | none | none | `server/discover` | `_meta` on **every** request, plus `Mcp-Method` / `Mcp-Name` headers |

The official MCP Kotlin SDK (0.15.0, checked 2026-10-21) targets a Kotlin
version this project's pinned toolchain (AGP 8.7.3 / Kotlin 2.0.21) cannot
consume. The Kotlin implementation is also listed in the lowest SDK tier, so
even a future-compatible release is not a stability guarantee.

## Decision

Keep `McpClientPort` as the seam and ship `MinimalMcpClient`, a
Streamable-HTTP JSON-RPC implementation of the subset this app uses:
`initialize`, `server/discover`, `tools/list`, `tools/call`,
`notifications/initialized`, `notifications/cancelled`.

### Constraints the implementation must keep

**1. Dual era, probed by outcome — not by error code.**

Probe legacy first; if `initialize` returns no `result`, fall back to
`server/discover`. The trigger is the *absence of a result*, deliberately, because
the reasons a server refuses the legacy era differ and all of them must land in
the same place:

| Response | Meaning |
|---|---|
| `-32022` | version rejected — an older server that refuses `2025-03-26` |
| **`-32601`** | **method not found — a strict 2026-07-28 server does not define `initialize` at all** |
| `-32602` | invalid params — some servers validate `capabilities` strictly |
| anything else, including a non-JSON body | unknown refusal |

Keying the fallback on one code would strand every server that refuses for a
reason we did not anticipate; keying it on "no result" is strictly more robust.
The codes are still *recorded* so a failed probe reports both sides:
`MCP connect failed (legacy error -32601, stateless error -32601): …`.

**2. Per-server serialization for the legacy era only, with a single probe.**

A sessionful server must not interleave requests, so legacy calls take a
`Mutex`; stateless calls are independent and must not be serialized, or one slow
server would throttle itself for no reason. The lock is waited on with a bound
(`LOCK_WAIT_TIMEOUT_MS`), never indefinitely.

The era probe itself is the one place that must NOT run twice concurrently:
two callers arriving before the era is decided would each perform a handshake,
and a sessionful server answers the second `initialize` by opening a second
session and invalidating the first. `connect()` therefore uses double-checked
locking — a lock-free `connected` fast path, the probe inside the mutex, and a
second `connected` check after acquiring it. Both `connected` and `info` are
`@Volatile` and written in that order, so a reader that sees `connected == true`
also sees `info`.

**3. Every stateless request describes itself.**

`_meta` is merged into `params` and `Mcp-Method` (plus `Mcp-Name` for
`tools/call`) is sent, on every request — not only on `server/discover`.
Sending them once is a protocol violation, not an optimisation.

**4. HTTP status is checked — with exactly one documented exception.**

A non-2xx answer becomes a typed `McpHttpException` carrying the status and a
redacted snippet, instead of a JSON-RPC parse failure that reads as
`"tools/call failed: null"`.

The exception is **404 on a legacy session**: a legacy session lives
server-side and expires, and 404 on the next request is the documented signal.
The client drops the stale `Mcp-Session-Id`, re-handshakes **once**, and retries
the same call (`withLegacySession`). A second 404 is real and propagates. This is
not a general retry policy: it is the only way an expired session recovers,
because nothing else clears the stale header.

`initialize` itself is deliberately exempt from the status check — a 4xx there
is the normal shape of "this server is not legacy", and it must reach the
era-fallback logic rather than throw.

**5. Cancellation is disconnect-first, and must not queue behind the call.**

`notifications/cancelled` has no ACK, so it is fire-and-forget.
`cancelInFlight()` exists so an in-flight `send` can be told to stop.

It reads a `@Volatile` request id and posts the notification **without acquiring
the session mutex**. That is a requirement, not an oversight: a legacy
`tools/call` holds the mutex for the whole call, so a cancelling path that
waited for the lock would block until `LOCK_WAIT_TIMEOUT_MS` — the cancel would
arrive after the thing it was cancelling had already finished. Cancellation is
the one message that must bypass the session serialization it is trying to
interrupt.

**6. Tool names are namespaced** as `<sanitizedPrefix>__<tool>`, and prefixes
never contain `__`, so `plainName()` is unambiguous.

## Consequences

- Swapping in the official SDK later is a one-file change behind the port —
  that is the whole point of the seam.
- We own the protocol surface: a new MCP revision is a change here, not a
  dependency bump. That is acceptable for the four methods used.
- The 404 re-handshake means a legacy server can recover from session expiry
  without the user re-adding it, at the cost of one extra round trip in that
  case only.

## Known gaps (precise)

- **POST responses carrying `Content-Type: text/event-stream` ARE supported.**
  Streamable HTTP wraps a JSON-RPC response inside an SSE event; `parseRpcPayload`
  unwraps `data:` lines, matches the JSON-RPC `id`, and falls back to the last
  result-bearing object. This path is covered by `McpParsingTest`.
- **Excluded: the reverse channel.** The optional `GET` stream, over which a
  server pushes unsolicited messages (notifications, sampling requests,
  server-initiated pings), is not opened. No tool in this app depends on it. If
  one appears, it is added behind the port — it does not change the request
  path above.
- No `resources/*` or `prompts/*` methods; only `tools/*`.
- `Mcp-Name` is sent for `tools/call` only, which is the only method where the
  spec requires it.

## Alternatives rejected

- **Official Kotlin SDK.** Incompatible with the pinned toolchain; would force
  a Kotlin/AGP upgrade across the whole app for one dependency.
- **JVM SDK / Node sidecar.** Adds a process and a runtime to an Android app.
- **HTTP-only, single era.** Silently breaks against half the servers in use.
- **Trusting a version string instead of probing.** Servers lie about
  `protocolVersion`, and a strict 2026-07-28 server has no `initialize` to ask.
