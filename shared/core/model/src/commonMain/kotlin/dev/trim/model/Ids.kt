package dev.trim.model

/** Stable identity of a video, assigned by the Scanner and never reused. */
@JvmInline
public value class VideoId(public val value: String) {
    init {
        require(value.isNotBlank()) { "VideoId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identity of a folder the user has granted access to. */
@JvmInline
public value class FolderId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FolderId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identity of a queued or running compression job. */
@JvmInline
public value class JobId(public val value: String) {
    init {
        require(value.isNotBlank()) { "JobId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * An opaque handle to a file in user storage. The core never parses it, joins it, or
 * builds one — only [dev.trim.ports.Storage] mints these, so the pipeline cannot
 * accidentally address a file the user did not grant.
 */
@JvmInline
public value class StorageRef(public val value: String) {
    init {
        require(value.isNotBlank()) { "StorageRef must not be blank" }
    }

    override fun toString(): String = value
}

/** A handle to scratch space owned by the app, never by the user. */
@JvmInline
public value class TempRef(public val value: String) {
    init {
        require(value.isNotBlank()) { "TempRef must not be blank" }
    }

    override fun toString(): String = value
}
