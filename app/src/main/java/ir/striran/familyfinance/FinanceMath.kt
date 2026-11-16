package ir.striran.familyfinance

internal fun checkedBalance(current: Long, delta: Long): Long? =
    try { Math.addExact(current, delta) } catch (_: ArithmeticException) { null }
