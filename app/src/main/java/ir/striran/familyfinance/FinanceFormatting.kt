package ir.striran.familyfinance

internal fun normalizeDigits(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
            in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
            in '0'..'9' -> append(ch)
            else -> Unit
        }
    }
}

internal fun formatAmount(value: String): String =
    normalizeDigits(value).reversed().chunked(3).joinToString(",").reversed()

internal fun parseAmount(value: String): Long? {
    if (value.isBlank()) return null
    if (value.any { it !in '۰'..'۹' && it !in '٠'..'٩' && it !in '0'..'9' && it !in charArrayOf(',', '٬', '،', ' ', '\u00A0') }) return null
    val digits = normalizeDigits(value)
    return digits.takeIf { it.isNotEmpty() }?.toLongOrNull()
}
