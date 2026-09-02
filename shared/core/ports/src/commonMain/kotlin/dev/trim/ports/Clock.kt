package dev.trim.ports

/**
 * Time, as a port. Nothing in the core calls a platform clock directly, so every
 * timeout, retention window and thermal poll interval is controllable from a test.
 */
public interface Clock {
    public fun nowEpochMs(): Long

    /** Suspends for [millis]. In tests this is virtual time. */
    public suspend fun sleep(millis: Long)
}
