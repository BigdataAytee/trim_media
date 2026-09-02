package dev.trim.ports

/**
 * Marks a [Storage] member that mutates user storage.
 *
 * This annotation is the authoritative list the build guard reads (app-architecture §8,
 * guard #3): adding a write-capable member to the port extends the guard automatically,
 * so there is no second list to forget to update. Calling an annotated member from
 * anywhere but the Replacer's package fails the build.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
public annotation class StorageWrite
