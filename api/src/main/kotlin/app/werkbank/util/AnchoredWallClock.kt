package app.werkbank.util

import io.opentelemetry.kotlin.Clock
import java.time.Instant

/**
 * An [io.opentelemetry.kotlin.Clock] with nanosecond resolution, replacing the SDK's default JVM
 * clock (`System.currentTimeMillis() * 1_000_000`).
 *
 * With only millisecond resolution every span that starts and ends within the same millisecond — the
 * common case for the proxy's own steps — is reported with a duration of exactly 0, which Jaeger
 * rejects as a non-positive duration ("negative duration detected, sanitizing end timestamp") and
 * fixes up to 1ns. Such spans carry no timing information at all.
 *
 * The epoch is read once and advanced by the monotonic clock from there, so span durations are exact.
 * The tradeoff is the one the OpenTelemetry Java SDK makes with its anchored clock: over a long
 * uptime the absolute timestamps can drift away from the wall clock, because the monotonic clock does
 * not follow its adjustments. Re-anchoring is deliberately not done — a backwards correction in the
 * middle of a span would produce exactly the negative duration this clock exists to avoid.
 */
class AnchoredWallClock : Clock {
    private val epochNanosAtAnchor = Instant.now().let { it.epochSecond * NANOS_PER_SECOND + it.nano }
    private val monotonicNanosAtAnchor = System.nanoTime()

    override fun now(): Long = epochNanosAtAnchor + (System.nanoTime() - monotonicNanosAtAnchor)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
