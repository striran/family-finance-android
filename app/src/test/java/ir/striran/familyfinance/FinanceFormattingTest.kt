package ir.striran.familyfinance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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

    @Test fun rejectsNegativeAndNonNumericInput() {\n        assertNull(parseAmount("-123"))\n        assertNull(parseAmount("12abc"))\n        assertNull(parseAmount("۱۲٫۵"))\n    }\n\n    @Test fun acceptsPersianGroupingSeparator() {\n        assertEquals(1234567L, parseAmount("۱٬۲۳۴٬۵۶۷"))\n    }\n\n    @Test fun rejectsOverflowAndEmptyInput() {
        assertNull(parseAmount("999999999999999999999999999"))
        assertNull(parseAmount(""))
    }
}
