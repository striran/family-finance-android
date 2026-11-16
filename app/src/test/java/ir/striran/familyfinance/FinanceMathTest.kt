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
}
