package com.botati.domain.security

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Bounded reads (BOT-06): read at most [cap] bytes, and fail as soon as the
 * stream exceeds it.
 *
 * The cap is enforced on the bytes actually read, never on a length a caller
 * declares: a `DocumentsProvider` is free to lie about `Content-Length`, and a
 * deflated ZIP entry declares nothing at all — which is exactly the zip-bomb
 * shape (a few KB of deflated zeros expanding to gigabytes). Allocating first
 * and checking afterwards is the bug this replaces.
 *
 * Lives in `domain/` on purpose: it is a safety primitive with no Android
 * dependency, so it is unit-tested directly rather than only through
 * `FileTools` (which needs a `Context`).
 */
object BoundedRead {

    private const val INITIAL_BUFFER_BYTES = 64 * 1024
    private const val READ_CHUNK_BYTES = 16 * 1024

    /**
     * @param input stream to drain; not closed by this function
     * @param cap   maximum number of bytes accepted
     * @param what  short label used in the failure message
     * @throws IllegalArgumentException when the stream yields more than [cap]
     */
    fun read(input: InputStream, cap: Int, what: String): ByteArray {
        require(cap > 0) { "cap must be positive" }
        val out = ByteArrayOutputStream(minOf(cap, INITIAL_BUFFER_BYTES))
        val buf = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) {
                throw IllegalArgumentException(
                    "$what exceeds the ${cap / (1024 * 1024)}MB extraction cap",
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
