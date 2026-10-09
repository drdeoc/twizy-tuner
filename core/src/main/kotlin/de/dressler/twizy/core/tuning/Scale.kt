package de.dressler.twizy.core.tuning

import kotlin.math.roundToInt

/** OVMS scale(): [default] scaled by [to]/[from], limited to [min]..[max]. */
internal fun scale(default: Long, from: Long, to: Long, min: Long, max: Long): Long {
    if (to == from) return default
    return (default * to / from).coerceIn(min, max)
}

/** Inverse of [scale] for reading settings back: which percentage gives [value]? */
internal fun unscale(value: Long, default: Long, defaultPercent: Int): Int =
    if (value == default) defaultPercent else (value * defaultPercent / default.toDouble()).roundToInt()
