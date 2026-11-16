package ir.striran.familyfinance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceFormattingTest {
    @Test fun balanceArithmeticRejectsOverflowAndUnderflow() {
        assertEquals(Long.MAX_VALUE, checkedBalance(Long.MAX_VALUE - 1, 1))
        assertNull(checkedBalance(Long.MAX_VALUE, 1))
        assertNull(checkedBalance(Long.MIN_VALUE, -1))
        assertEquals(50L, checkedBalance(100L, -50L))
    }

    @Test fun formatsPersianDigitsWithThousandsSeparators() {
        assertEquals("1,234,567", formatAmount("۱۲۳۴۵۶۷"))
    }

    @Test fun formatsArabicIndicDigits() {
        assertEquals("123,456", formatAmount("١٢٣٤٥٦"))
    }

    @Test fun normalizesAlreadyGroupedInput() {
        assertEquals("1,234,567", formatAmount("1,234,567"))
    }

    @Test fun parsesPersianAndArabicDigits() {
        assertEquals(1234L, parseAmount("۱,۲۳۴"))
        assertEquals(9876L, parseAmount("٩٨٧٦"))
    }

    @Test fun rejectsNegativeAndNonNumericInput() {
        assertNull(parseAmount("-123"))
        assertNull(parseAmount("12abc"))
        assertNull(parseAmount("۱۲٫۵"))
    }

    @Test fun acceptsPersianGroupingSeparator() {
        assertEquals(1234567L, parseAmount("۱٬۲۳۴٬۵۶۷"))
    }

    @Test fun rejectsOverflowAndEmptyInput() {
        assertNull(parseAmount("999999999999999999999999999"))
        assertNull(parseAmount(""))
    }
}
