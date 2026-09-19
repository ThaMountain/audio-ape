package com.audioape.plugin.fixtures

/**
 * Injectable epoch-millis clock for the resolver simulator, mirroring the injected
 * `now: () -> Instant` seam of the playback checkpoint recorder. Pure JVM and
 * deterministic: no timers, no wall-clock reads, no network.
 */
fun interface FixtureResolverClock {
    fun nowMillis(): Long
}

/** Productive default: the real epoch-millis wall clock. Only the fixture prod build uses it. */
val systemEpochMillisClock: FixtureResolverClock = FixtureResolverClock { System.currentTimeMillis() }
