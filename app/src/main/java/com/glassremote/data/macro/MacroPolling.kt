package com.glassremote.data.macro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Waits for a real device-state predicate instead of relying on a guessed fixed sleep. */
suspend fun awaitMacroCondition(
    description: String,
    timeoutMs: Long = 4_000,
    pollIntervalMs: Long = 100,
    condition: suspend () -> Boolean,
) {
    require(timeoutMs in 1L..60_000L) { "timeoutMs must be 1–60000" }
    require(pollIntervalMs in 25L..2_000L) { "pollIntervalMs must be 25–2000" }
    try {
        withTimeout(timeoutMs) {
            while (!condition()) delay(pollIntervalMs)
        }
    } catch (cancelled: CancellationException) {
        // Preserve user cancellation. Callers can translate TimeoutCancellationException
        // into a step-specific timeout error at the macro orchestration boundary.
        throw cancelled
    }
}
