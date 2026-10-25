package com.botati.policy

import com.botati.domain.policy.DataCollection
import com.botati.domain.policy.dataCollectionFor
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
}
