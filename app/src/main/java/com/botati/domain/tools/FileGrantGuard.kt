package com.botati.domain.tools

import java.net.URLDecoder

/**
 * SAF grant enforcement (spec §28): the check lives in the Tool Router layer,
 * NOT inside read_file, so every future file tool inherits it. A target
 * document URI is granted iff it is a granted document itself, or lives under
 * a granted tree. Comparison is on decoded document ids.
 *
 * BOT-11: two things the previous comparison ignored —
 *  - the AUTHORITY. `content://evil.provider/...` and
 *    `content://com.android.externalstorage.documents/...` can carry the same
 *    document id, and a grant for one must not authorize the other.
 *  - traversal. `primary:Docs/../Private/x` has the `primary:Docs` prefix but
 *    does not live inside it, so a plain prefix test let it through. Dot
 *    segments are now rejected outright: a real document id never contains one.
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

    /** `content://<authority>/...` → `<authority>`; null for anything else. */
    fun authorityOf(uri: String): String? {
        val scheme = uri.indexOf("://")
        if (scheme < 0) return null
        val rest = uri.substring(scheme + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        return authority.ifBlank { null }
    }

    /** A document id containing a dot segment is never legitimate. */
    private fun hasTraversal(id: String): Boolean =
        id.split('/').any { it == ".." || it == "." }

    fun isGranted(targetUri: String, grants: List<String>): Boolean {
        val targetId = idOf(targetUri) ?: return false
        if (hasTraversal(targetId)) return false
        val targetAuthority = authorityOf(targetUri)
        for (grant in grants) {
            if (targetAuthority != null && authorityOf(grant) != targetAuthority) continue
            val grantId = idOf(grant) ?: continue
            if (hasTraversal(grantId)) continue
            if (targetId == grantId) return true
            // Under a granted tree: tree "primary:Docs" covers "primary:Docs/a.txt".
            if (targetId.startsWith("$grantId/")) return true
        }
        return false
    }
}
