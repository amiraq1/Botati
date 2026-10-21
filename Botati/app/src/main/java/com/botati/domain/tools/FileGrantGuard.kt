package com.botati.domain.tools

import java.net.URLDecoder

/**
 * SAF grant enforcement (spec §28): the check lives in the Tool Router layer,
 * NOT inside read_file, so every future file tool inherits it. A target
 * document URI is granted iff it is a granted document itself, or lives under
 * a granted tree. Comparison is on decoded document ids.
 */
object FileGrantGuard {

    private fun docId(uri: String, marker: String): String? {
        val idx = uri.indexOf(marker)
        if (idx < 0) return null
        val raw = uri.substring(idx + marker.length).substringBefore('?')
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
    }

    /** Document id of a document URI or of a tree URI. */
    fun idOf(uri: String): String? =
        docId(uri, "/document/") ?: docId(uri, "/tree/")

    fun isGranted(targetUri: String, grants: List<String>): Boolean {
        val targetId = idOf(targetUri) ?: return false
        for (grant in grants) {
            val grantId = idOf(grant) ?: continue
            if (targetId == grantId) return true
            // Under a granted tree: tree "primary:Docs" covers "primary:Docs/a.txt".
            if (targetId.startsWith("$grantId/")) return true
        }
        return false
    }
}
