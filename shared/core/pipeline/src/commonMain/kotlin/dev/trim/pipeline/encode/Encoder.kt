package dev.trim.pipeline.encode

import dev.trim.model.ColorRange
import dev.trim.model.EncodeSetting
import dev.trim.model.FailureReason
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.pipeline.toFailureReason
import dev.trim.ports.Codec
import dev.trim.ports.CodecError
import dev.trim.ports.FullEncodeResult

/**
 * Orchestration only. Every hardware decision — Surface-to-Surface path, audio stream
 * copy, keyframe interval, `KEY_PRIORITY = 1` — belongs to the Codec implementation
 * (app-architecture §3); this class exists to own the *policy* around one encode: emit
 * progress, and hand a reclaimed codec back to the runner rather than treating it as a
 * file-level failure.
 *
 * The distinction matters because it is the difference between a paused job and a lost
 * one (app-architecture §10). [EncodeOutcome.Interrupted] is the only outcome the runner
 * is allowed to retry.
 */
public class Encoder(
    private val codec: Codec,
) {
    public suspend fun encode(
        source: StorageRef,
        fingerprint: SourceFingerprint,
        setting: EncodeSetting,
        destination: TempRef,
        onProgress: (Double) -> Unit,
    ): EncodeOutcome {
        val result = codec.encodeFull(source, setting, destination, onProgress)
        return when (result) {
            is FullEncodeResult.Encoded -> EncodeOutcome.Encoded(
                bytes = result.bytes,
                durationMs = result.durationMs,
                colorRange = result.colorRange,
            )
            is FullEncodeResult.Failed -> when (val error = result.error) {
                is CodecError.CodecReclaimed -> EncodeOutcome.Interrupted(error.atFraction)
                else -> EncodeOutcome.Failed(error.toFailureReason(fingerprint))
            }
        }
    }
}

public sealed interface EncodeOutcome {
    public data class Encoded(
        val bytes: Long,
        val durationMs: Long,
        val colorRange: ColorRange,
    ) : EncodeOutcome

    /** The OS took the encoder back at [atFraction]. The runner waits and resumes. */
    public data class Interrupted(val atFraction: Double) : EncodeOutcome

    public data class Failed(val reason: FailureReason) : EncodeOutcome
}
