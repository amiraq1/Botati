package com.botati.agent

import com.botati.domain.agent.CostSource
import com.botati.domain.agent.RawUsage
import com.botati.domain.agent.UsageAccounting
import com.botati.domain.agent.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAccountingTest {
    @Test fun providerCost_isActual() {
        val r = UsageAccounting.resolve("any/model", RawUsage(100, 50, costUsd = 0.012))
        assertEquals(CostSource.ACTUAL, r.source)
        assertEquals(0.012, r.costUsd!!, 1e-9)
    }

    @Test fun missingCost_knownModel_isEstimatedNotZero() {
        val r = UsageAccounting.resolve("openai/gpt-4o-mini", RawUsage(1_000_000, 0, costUsd = null))
        assertEquals(CostSource.ESTIMATED, r.source)
        assertEquals(0.15, r.costUsd!!, 1e-9)
    }

    @Test fun missingCost_unknownModel_isUnknownNull_neverSilentZero() {
        val r = UsageAccounting.resolve("some/free-model", RawUsage(500, 500, costUsd = null))
        assertEquals(CostSource.UNKNOWN, r.source)
        assertNull(r.costUsd)
    }

    @Test fun tracker_stopsAfterThreeConsecutiveUnknown() {
        val t = UsageTracker()
        repeat(2) { t.record("some/free-model", RawUsage(1, 1, null)) }
        assertTrue(!t.shouldStopForUnknownCost())
        t.record("some/free-model", RawUsage(1, 1, null))
        assertTrue(t.shouldStopForUnknownCost())
    }

    @Test fun tracker_actualCostResetsUnknownStreak() {
        val t = UsageTracker()
        repeat(2) { t.record("some/free-model", RawUsage(1, 1, null)) }
        t.record("any/model", RawUsage(1, 1, 0.01))
        repeat(2) { t.record("some/free-model", RawUsage(1, 1, null)) }
        assertTrue(!t.shouldStopForUnknownCost())
        assertEquals(0.01, t.totalKnownCostUsd, 1e-9)
    }
}
