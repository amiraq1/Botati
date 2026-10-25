package com.botati.infra

import com.botati.infrastructure.mcp.MinimalMcpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP-01 (owner decision 2026-10-25, Q5): the stateless 2026-07-28 shape.
 * The transport itself (LEGACY 404 re-initialize) is not unit-testable here —
 * no MockEngine in test-deps and the client requires https — so these tests
 * pin the pure request builders. The retry path needs the live integration
 * test proposed in the review (a local Streamable HTTP server in CI).
 */
class McpStatelessTest {

    @Test fun statelessMeta_hasProtocolVersionClientInfoAndCapabilities() {
        val meta = MinimalMcpClient.statelessMeta()
        assertEquals(
            "2026-07-28",
            (meta["io.modelcontextprotocol/protocolVersion"] as JsonPrimitive).contentOrNull,
        )
        val info = meta["io.modelcontextprotocol/clientInfo"] as JsonObject
        assertEquals("Botati", (info["name"] as JsonPrimitive).contentOrNull)
        assertEquals(MinimalMcpClient.CLIENT_VERSION, (info["version"] as JsonPrimitive).contentOrNull)
        assertNotNull(meta["io.modelcontextprotocol/clientCapabilities"])
    }

    @Test fun withStatelessMeta_preservesExistingParams() {
        val params = buildJsonObject { put("name", "send_reply") }
        val merged = MinimalMcpClient.withStatelessMeta(params)
        assertEquals("send_reply", (merged["name"] as JsonPrimitive).contentOrNull)
        assertNotNull(merged["_meta"])
        // The original params are untouched.
        assertNull(params["_meta"])
    }

    @Test fun statelessHeaders_includeMcpNameForToolsCall() {
        val headers = MinimalMcpClient.statelessHeaders("tools/call", "send_reply")
        assertEquals("2026-07-28", headers["MCP-Protocol-Version"])
        assertEquals("tools/call", headers["Mcp-Method"])
        assertEquals("send_reply", headers["Mcp-Name"])
    }

    @Test fun statelessHeaders_omitMcpNameWhenNull() {
        val headers = MinimalMcpClient.statelessHeaders("tools/list", null)
        assertEquals("2026-07-28", headers["MCP-Protocol-Version"])
        assertEquals("tools/list", headers["Mcp-Method"])
        assertFalse(headers.containsKey("Mcp-Name"))
    }

    @Test fun clientVersion_isCurrentRelease() {
        // Pinned so a version bump cannot silently drift from versionName.
        assertEquals("0.1.4", MinimalMcpClient.CLIENT_VERSION)
    }
}
