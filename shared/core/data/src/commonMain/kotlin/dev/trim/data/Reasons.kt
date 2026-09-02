package dev.trim.data

import dev.trim.model.FailureReason
import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.RejectionReason
import dev.trim.model.SkipReason
import dev.trim.model.TransferFunction
import dev.trim.model.VerificationFailure
import dev.trim.model.VideoCodec

/**
 * Reasons on their way to and from a `(type, detail)` column pair.
 *
 * The round trip is lossy on purpose: the detail column keeps the numbers a diagnostic
 * export would want, but a reason read back is reconstructed as the same *case* with
 * whatever numbers survived. What must never be lost is the case itself — a row that says
 * only "skipped" cannot be written, because [encodeType] is total over the sealed
 * hierarchy and the column is NOT NULL.
 */
public object Reasons {

    public fun encodeType(reason: RejectionReason): String = when (reason) {
        is SkipReason.AlreadyEfficient -> "already_efficient"
        is SkipReason.TooNoisy -> "too_noisy"
        is SkipReason.Hdr -> "hdr"
        is SkipReason.SecondaryTrack -> "secondary_track"
        is SkipReason.TooSmall -> "too_small"
        is SkipReason.NoHeadroom -> "no_headroom"
        is SkipReason.CannotReachTarget -> "cannot_reach_target"
        is FailureReason.VerificationFailed -> when (reason.detail) {
            is VerificationFailure.NotSmaller -> "verify_not_smaller"
            is VerificationFailure.DurationChanged -> "verify_duration_changed"
            is VerificationFailure.ScoreBelowTarget -> "verify_score_below_target"
            is VerificationFailure.ColorRangeChanged -> "verify_color_range_changed"
        }
        is FailureReason.SourceChanged -> "source_changed"
        is FailureReason.EncoderError -> "encoder_error"
        is FailureReason.StorageError -> "storage_error"
        is FailureReason.OutOfSpace -> "out_of_space"
        FailureReason.Cancelled -> "cancelled"
        is FailureReason.ReplaceRolledBack -> "replace_rolled_back"
    }

    public fun encodeDetail(reason: RejectionReason): String = when (reason) {
        is SkipReason.AlreadyEfficient -> "${reason.codec.name}:${reason.bitsPerPixelPerSecond}"
        is SkipReason.TooNoisy -> reason.noiseEnergy.toString()
        is SkipReason.Hdr -> "${reason.transfer.name}:${reason.bitDepth}"
        is SkipReason.SecondaryTrack ->
            "${reason.videoTracks}:${reason.audioTracks}:${reason.otherTracks}"
        is SkipReason.TooSmall -> "${reason.sizeBytes}:${reason.minimumBytes}"
        is SkipReason.NoHeadroom -> "${reason.ceiling.value}:${reason.required.value}"
        is SkipReason.CannotReachTarget -> "${reason.bestScore.value}:${reason.target.value}"
        is FailureReason.VerificationFailed -> when (val failure = reason.detail) {
            is VerificationFailure.NotSmaller ->
                "${failure.originalBytes}:${failure.encodedBytes}"
            is VerificationFailure.DurationChanged ->
                "${failure.expectedMs}:${failure.actualMs}"
            is VerificationFailure.ScoreBelowTarget ->
                "${failure.score.value}:${failure.target.value}"
            is VerificationFailure.ColorRangeChanged ->
                "${failure.expected.name}:${failure.actual.name}"
        }
        is FailureReason.SourceChanged -> reason.expected.headTailHash
        is FailureReason.EncoderError -> reason.detail
        is FailureReason.StorageError -> reason.detail
        is FailureReason.OutOfSpace -> "${reason.neededBytes}:${reason.availableBytes}"
        FailureReason.Cancelled -> ""
        is FailureReason.ReplaceRolledBack ->
            "${reason.failedStep}:${reason.failedStepName}:${reason.detail}"
    }

    /** Reconstructs a skip. Returns null for a stored failure, which is not a skip. */
    public fun decodeSkip(type: String, detail: String): SkipReason? {
        val parts = detail.split(':')
        return when (type) {
            "already_efficient" -> SkipReason.AlreadyEfficient(
                codec = runCatching { VideoCodec.valueOf(parts[0]) }.getOrDefault(VideoCodec.UNKNOWN),
                bitsPerPixelPerSecond = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            )
            "too_noisy" -> SkipReason.TooNoisy(detail.toDoubleOrNull() ?: 0.0)
            "hdr" -> SkipReason.Hdr(
                transfer = runCatching { TransferFunction.valueOf(parts[0]) }
                    .getOrDefault(TransferFunction.PQ),
                bitDepth = parts.getOrNull(1)?.toIntOrNull() ?: 10,
            )
            "secondary_track" -> SkipReason.SecondaryTrack(
                videoTracks = parts.getOrNull(0)?.toIntOrNull() ?: 1,
                audioTracks = parts.getOrNull(1)?.toIntOrNull() ?: 1,
                otherTracks = parts.getOrNull(2)?.toIntOrNull() ?: 1,
            )
            "too_small" -> SkipReason.TooSmall(
                sizeBytes = parts.getOrNull(0)?.toLongOrNull() ?: 0,
                minimumBytes = parts.getOrNull(1)?.toLongOrNull() ?: 0,
            )
            "no_headroom" -> SkipReason.NoHeadroom(
                ceiling = score(parts.getOrNull(0)),
                required = score(parts.getOrNull(1)),
            )
            "cannot_reach_target" -> SkipReason.CannotReachTarget(
                bestScore = score(parts.getOrNull(0), Metric.XPSNR),
                target = score(parts.getOrNull(1), Metric.XPSNR),
            )
            else -> null
        }
    }

    /**
     * Reconstructs a failure. The *case* always survives — including which verification
     * check failed — because the type column encodes it; free-text detail comes back as
     * the text it was. A decoded failure is for display and diagnostics, never for driving
     * a retry.
     */
    public fun decodeFailure(type: String, detail: String): FailureReason? = when (type) {
        "verify_not_smaller" -> detail.split(':').let { parts ->
            FailureReason.VerificationFailed(
                VerificationFailure.NotSmaller(
                    originalBytes = parts.getOrNull(0)?.toLongOrNull() ?: 0,
                    encodedBytes = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                ),
            )
        }
        "verify_duration_changed" -> detail.split(':').let { parts ->
            FailureReason.VerificationFailed(
                VerificationFailure.DurationChanged(
                    expectedMs = parts.getOrNull(0)?.toLongOrNull() ?: 0,
                    actualMs = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                ),
            )
        }
        "verify_score_below_target" -> detail.split(':').let { parts ->
            FailureReason.VerificationFailed(
                VerificationFailure.ScoreBelowTarget(
                    score = score(parts.getOrNull(0)),
                    target = score(parts.getOrNull(1)),
                ),
            )
        }
        "verify_color_range_changed" -> detail.split(':').let { parts ->
            FailureReason.VerificationFailed(
                VerificationFailure.ColorRangeChanged(
                    expected = colorRange(parts.getOrNull(0)),
                    actual = colorRange(parts.getOrNull(1)),
                ),
            )
        }
        "source_changed" -> FailureReason.SourceChanged(
            expected = dev.trim.model.SourceFingerprint(0, 0, detail.ifBlank { "unknown" }),
            actual = null,
        )
        "encoder_error" -> FailureReason.EncoderError(detail)
        "storage_error" -> FailureReason.StorageError(detail)
        "out_of_space" -> detail.split(':').let { parts ->
            FailureReason.OutOfSpace(
                neededBytes = parts.getOrNull(0)?.toLongOrNull() ?: 0,
                availableBytes = parts.getOrNull(1)?.toLongOrNull() ?: 0,
            )
        }
        "cancelled" -> FailureReason.Cancelled
        "replace_rolled_back" -> detail.split(':').let { parts ->
            FailureReason.ReplaceRolledBack(
                failedStep = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                failedStepName = parts.getOrNull(1).orEmpty(),
                detail = parts.drop(2).joinToString(":"),
            )
        }
        else -> null
    }

    /** Either kind, for the one place that renders both: History's skipped list. */
    public fun decode(type: String, detail: String): RejectionReason? =
        decodeSkip(type, detail) ?: decodeFailure(type, detail)

    private fun colorRange(raw: String?): dev.trim.model.ColorRange =
        runCatching { dev.trim.model.ColorRange.valueOf(raw.orEmpty()) }
            .getOrDefault(dev.trim.model.ColorRange.LIMITED)

    private fun score(raw: String?, metric: Metric = Metric.VMAF): QualityScore =
        QualityScore(metric, raw?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0)
}
