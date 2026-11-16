package ir.striran.familyfinance

import kotlin.math.roundToInt

internal fun checkedBalance(current: Long, delta: Long): Long? =
    try { Math.addExact(current, delta) } catch (_: ArithmeticException) { null }

internal fun budgetUsagePercent(spent: Long, limit: Long): Int =
    if (limit <= 0L || spent <= 0L) 0
    else (spent.toDouble() / limit.toDouble() * 100.0).roundToInt().coerceAtLeast(0)

internal fun budgetRemaining(spent: Long, limit: Long): Long =
    if (limit <= spent) 0L else limit - spent

internal fun budgetOverage(spent: Long, limit: Long): Long =
    if (spent <= limit) 0L else spent - limit
