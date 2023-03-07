package dev.evo.prometheus.util

expect inline fun measureTimeMillis(block: () -> Unit): Double

expect inline fun <T> measureTimeMillisWithResult(block: () -> T): Pair<T, Double>