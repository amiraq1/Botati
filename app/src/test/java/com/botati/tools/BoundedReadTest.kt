package com.botati.tools

import com.botati.domain.security.BoundedRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.util.Arrays

/**
 * BOT-06: bounded reads.
 *
 * The guard is a pure domain primitive ([BoundedRead]) precisely so this can run
 * without an Android `Context` — the old version allocated the whole file and
 * only then checked the cap, and the docx `document.xml` read had no cap at all.
 */
class BoundedReadTest {

    /** A stream that never ends — the zip-bomb / hostile-provider shape. */
    private fun endlessStream(): InputStream = object : InputStream() {
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Arrays.fill(b, off, off + len, 0)
            return len
        }
    }

    @Test fun rejectsAStreamThatExceedsTheCap() {
        try {
            BoundedRead.read(endlessStream(), 4_096, "test")
            fail("expected the cap to be enforced")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("extraction cap"))
        }
    }

    @Test fun acceptsAStreamExactlyAtTheCap() {
        val cap = 4_096
        val exact = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < cap) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= cap) return -1
                val n = minOf(len, cap - sent)
                Arrays.fill(b, off, off + n, 0)
                sent += n
                return n
            }
        }
        assertEquals(cap, BoundedRead.read(exact, cap, "test").size)
    }

    @Test fun stopsAtEndOfStream() {
        val short = "hello".byteInputStream()
        assertEquals("hello", String(BoundedRead.read(short, 1_024, "test")))
    }

    @Test fun rejectsANonPositiveCap() {
        try {
            BoundedRead.read("x".byteInputStream(), 0, "test")
            fail("a zero cap is a programming error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("positive"))
        }
    }
}
