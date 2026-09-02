package dev.trim.pipeline

import dev.trim.model.FailureReason
import dev.trim.model.SourceFingerprint
import dev.trim.ports.CodecError

/**
 * Maps a codec error onto the error taxonomy of app-architecture §10.
 *
 * [CodecError.CodecReclaimed] deliberately has no mapping here: it is a *retryable
 * interruption* owned by the runner, not a file-level failure, and turning it into one
 * would lose the file for a reason the OS considers routine. Callers must handle it
 * before reaching this function.
 */
public fun CodecError.toFailureReason(expected: SourceFingerprint): FailureReason =
    when (this) {
        is CodecError.CodecReclaimed -> FailureReason.EncoderError(
            "codec reclaimed at $atFraction and not retried — this is a runner bug, not a file " +
                "failure (app-architecture §10)",
        )
        is CodecError.NoHardwareSupport -> FailureReason.EncoderError(detail)
        CodecError.SourceChanged -> FailureReason.SourceChanged(expected, null)
        is CodecError.OutOfSpace -> FailureReason.OutOfSpace(neededBytes, availableBytes)
        is CodecError.Fatal -> FailureReason.EncoderError(detail)
    }
