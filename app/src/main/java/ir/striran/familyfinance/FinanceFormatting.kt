package ir.striran.familyfinance

internal fun normalizeDigits(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
            in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
            else -> if (ch in '0'..'9') append(ch)
        }
    }
}

internal fun formatAmount(value: String): String =
    normalizeDigits(value).reversed().chunked(3).joinToString(",").reversed()

internal fun parseAmount(value: String): Long? = normalizeDigits(value).toLongOrNull()
