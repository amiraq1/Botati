package com.botati.tools

import com.botati.domain.tools.FileGrantGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileGrantGuardTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocs"
    private val docInTree =
        "content://com.android.externalstorage.documents/document/primary%3ADocs%2Fnotes.txt"
    private val docOutside =
        "content://com.android.externalstorage.documents/document/primary%3APrivate%2Fsecret.txt"
    private val singleDoc =
        "content://com.android.externalstorage.documents/document/primary%3Aone.csv"

    @Test fun documentUnderGrantedTree_allowed() {
        assertTrue(FileGrantGuard.isGranted(docInTree, listOf(tree)))
    }

    @Test fun documentOutsideTree_denied() {
        assertFalse(FileGrantGuard.isGranted(docOutside, listOf(tree)))
    }

    @Test fun exactSingleDocumentGrant_allowed() {
        assertTrue(FileGrantGuard.isGranted(singleDoc, listOf(singleDoc)))
        assertFalse(FileGrantGuard.isGranted(docInTree, listOf(singleDoc)))
    }

    @Test fun siblingPrefix_isNotUnderTree() {
        // "Docs2" must not match the "Docs" grant by string-prefix accident.
        val sibling = "content://com.android.externalstorage.documents/document/primary%3ADocs2%2Fx.txt"
        assertFalse(FileGrantGuard.isGranted(sibling, listOf(tree)))
    }

    // ---------- BOT-11 ----------

    @Test fun traversalSegment_isRejected_evenWithTheRightPrefix() {
        // Decodes to "primary:Docs/../Private/secret.txt" — has the granted
        // prefix but does not live inside the tree.
        val sneaky =
            "content://com.android.externalstorage.documents/document/primary%3ADocs%2F..%2FPrivate%2Fsecret.txt"
        assertFalse(FileGrantGuard.isGranted(sneaky, listOf(tree)))
    }

    @Test fun singleDotSegment_isRejected() {
        val sneaky =
            "content://com.android.externalstorage.documents/document/primary%3ADocs%2F.%2Fnotes.txt"
        assertFalse(FileGrantGuard.isGranted(sneaky, listOf(tree)))
    }

    @Test fun differentAuthority_isNotCoveredByTheGrant() {
        // Same document id, different provider: a grant for one authority must
        // never authorize the other.
        val other = "content://com.example.evilprovider/document/primary%3ADocs%2Fnotes.txt"
        assertFalse(FileGrantGuard.isGranted(other, listOf(tree)))
    }

    @Test fun authority_isParsedFromTheUri() {
        assertEquals(
            "com.android.externalstorage.documents",
            FileGrantGuard.authorityOf(tree),
        )
        org.junit.Assert.assertNull(FileGrantGuard.authorityOf("/sdcard/notes.txt"))
    }
}
