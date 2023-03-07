package dev.evo.prometheus.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.system.measureNanoTime

actual inline fun measureTimeMillis(block: () -> Unit): Double = measureNanoTime { block() }.toDouble() / 1_000_000.0

actual inline fun <T> measureTimeMillisWithResult(block: () -> T): Pair<T, Double> {
    val (result, duration) = dev.evo.prometheus.util.measureNanoTime { block() }
    return Pair(result, duration.toDouble() / 1_000_000.0)
}

@OptIn(ExperimentalContracts::class)
inline fun <T> measureNanoTime(block: () -> T): Pair<T, Long> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val start = System.nanoTime()
    val result = block()
    return Pair(result, System.nanoTime() - start)
}