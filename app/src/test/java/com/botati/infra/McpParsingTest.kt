package com.botati.infra

import com.botati.infrastructure.mcp.MinimalMcpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McpParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun plainJsonResponse_parsed() {
        val obj = MinimalMcpClient.parseRpcPayload(
            """{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""", 3, json,
        )
        assertNotNull(obj)
        assertNotNull((obj as JsonObject)["result"])
    }

    @Test fun sseStream_picksMatchingId() {
        val sse = """
            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"a":1}}

            event: message
            data: {"jsonrpc":"2.0","id":2,"result":{"b":2}}
        """.trimIndent()
        val obj = MinimalMcpClient.parseRpcPayload(sse, 2, json) as JsonObject
        val result = obj["result"] as JsonObject
        assertEquals("2", (result["b"] as JsonPrimitive).contentOrNull)
    }

    @Test fun emptyBody_returnsNull() {
        assertNull(MinimalMcpClient.parseRpcPayload("", 1, json))
        assertNull(MinimalMcpClient.parseRpcPayload("not json", 1, json))
    }

    // F-07: MinimalMcpClient must reject non-https URLs
    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpUrl() {
        MinimalMcpClient.validateUrl("http://insecure.example.com")
    }
}
