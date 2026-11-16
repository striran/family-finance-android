package ir.striran.familyfinance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceMathTest {
    @Test fun addsAndSubtractsWithinLongRange() {
        assertEquals(150L, checkedBalance(100L, 50L))
        assertEquals(50L, checkedBalance(100L, -50L))
        assertEquals(0L, checkedBalance(0L, 0L))
    }

    @Test fun rejectsPositiveOverflow() {
        assertNull(checkedBalance(Long.MAX_VALUE, 1L))
        assertNull(checkedBalance(Long.MAX_VALUE - 2L, 3L))
    }

    @Test fun rejectsNegativeUnderflow() {
        assertNull(checkedBalance(Long.MIN_VALUE, -1L))
        assertNull(checkedBalance(Long.MIN_VALUE + 2L, -3L))
    }

    @Test fun acceptsExactLongBoundaries() {
        assertEquals(Long.MAX_VALUE, checkedBalance(Long.MAX_VALUE - 1L, 1L))
        assertEquals(Long.MIN_VALUE, checkedBalance(Long.MIN_VALUE + 1L, -1L))
    }

    @Test fun calculatesRemainingBudgetAndOverage() {
        assertEquals(600L, budgetRemaining(400L, 1_000L))
        assertEquals(0L, budgetRemaining(1_200L, 1_000L))
        assertEquals(0L, budgetOverage(800L, 1_000L))
        assertEquals(200L, budgetOverage(1_200L, 1_000L))
    }

    @Test fun calculatesBudgetUsagePercentIncludingOverLimit() {
        assertEquals(0, budgetUsagePercent(0L, 1_000L))
        assertEquals(45, budgetUsagePercent(450L, 1_000L))
        assertEquals(100, budgetUsagePercent(1_000L, 1_000L))
        assertEquals(125, budgetUsagePercent(1_250L, 1_000L))
        assertEquals(0, budgetUsagePercent(100L, 0L))
        assertEquals(0, budgetUsagePercent(-10L, 1_000L))
    }
}
