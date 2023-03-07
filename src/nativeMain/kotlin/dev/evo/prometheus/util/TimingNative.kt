package dev.evo.prometheus.util

import kotlin.system.getTimeNanos
import kotlin.system.measureNanoTime

actual inline fun measureTimeMillis(block: () -> Unit): Double = measureNanoTime { block() }.toDouble() / 1_000_000.0

actual inline fun <T> measureTimeMillisWithResult(block: () -> T): Pair<T, Double> {
    val (result, duration) = dev.evo.prometheus.util.measureNanoTime { block() }
    return Pair(result, duration.toDouble() / 1_000_000.0)
}

inline fun <T> measureNanoTime(block: () -> T): Pair<T, Long> {
    val start = getTimeNanos()
    val result = block()
    return Pair(result, getTimeNanos() - start)
}
