package com.botati.policy

import com.botati.domain.policy.DataCollection
import com.botati.domain.policy.dataCollectionFor
import com.botati.domain.policy.isDenyCompatibleFreeModel
import com.botati.domain.policy.isFreeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §18/Q4: the routing decision table. Only (free, clean) is Allow. */
class DataCollectionTest {

    @Test fun freeModel_cleanSession_allows() {
        assertEquals(DataCollection.Allow, dataCollectionFor("google/gemma-4-31b-it:free", false))
    }

    @Test fun freeModel_contaminatedSession_denies() {
        assertEquals(DataCollection.Deny, dataCollectionFor("google/gemma-4-31b-it:free", true))
    }

    @Test fun paidModel_cleanSession_denies() {
        assertEquals(DataCollection.Deny, dataCollectionFor("openai/gpt-4o-mini", false))
    }

    @Test fun paidModel_contaminatedSession_denies() {
        assertEquals(DataCollection.Deny, dataCollectionFor("openai/gpt-4o-mini", true))
    }

    @Test fun freeSuffix_isCaseInsensitiveAndTrimmed() {
        assertEquals(DataCollection.Allow, dataCollectionFor("  model:FREE  ", false))
        assertTrue(isFreeModel("MODEL:FREE"))
    }

    @Test fun blankOrUnknownModel_denies() {
        assertEquals(DataCollection.Deny, dataCollectionFor("", false))
        assertEquals(DataCollection.Deny, dataCollectionFor("   ", false))
        assertFalse(isFreeModel(""))
    }

    // §18/Q4 (2026-10-26): only the three free models with a live-observed
    // successful reply under deny are allowlisted for D2 content.
    @Test fun denyCompatibleFreeModels_areAllowlisted() {
        assertTrue(isDenyCompatibleFreeModel("recursive/flint-v1.0:free"))
        assertTrue(isDenyCompatibleFreeModel("dots-studio/dots-3-note-preview:free"))
        assertTrue(isDenyCompatibleFreeModel("cohere/north-mini-code:free"))
        assertTrue(isDenyCompatibleFreeModel("  Recursive/Flint-V1.0:Free  "))
    }

    @Test fun denyIncompatibleFreeModels_areNotAllowlisted() {
        // Routed under deny, but only upstream 429s were observed — not
        // confirmed, so it stays fail-closed until a deny reply is seen.
        assertFalse(isDenyCompatibleFreeModel("google/gemma-4-31b-it:free"))
        // 404 "No endpoints found matching your data policy" under deny.
        assertFalse(isDenyCompatibleFreeModel("liquid/lfm-2.5-2.6b:free"))
        assertFalse(isDenyCompatibleFreeModel("openai/gpt-4o-mini"))
        assertFalse(isDenyCompatibleFreeModel(""))
    }
}
