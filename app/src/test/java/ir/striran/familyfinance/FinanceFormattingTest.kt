package ir.striran.familyfinance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceFormattingTest {
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

    @Test fun rejectsOverflowAndEmptyInput() {
        assertNull(parseAmount("999999999999999999999999999"))
        assertNull(parseAmount(""))
    }
}
