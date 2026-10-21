package com.botati.tools

import com.botati.domain.tools.FileGrantGuard
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
}
