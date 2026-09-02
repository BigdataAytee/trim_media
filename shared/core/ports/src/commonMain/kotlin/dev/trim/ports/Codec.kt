package dev.trim.ports

import dev.trim.model.ColorRange
import dev.trim.model.EncodeSetting
import dev.trim.model.StorageRef
import dev.trim.model.TempRef

/**
 * The hardware encoder and decoder, obtained only through a CodecFactory on the platform
 * side (guard #2). There is no software fallback to ask for: a file the hardware cannot
 * handle is skipped with a reason (app-architecture §12).
 *
 * The port speaks in *windows* and *whole files*, never in frames — frames never cross
 * this boundary, because the zero-copy path (§5) keeps them inside the platform layer.
 */
public interface Codec {

    /**
     * Decodes a short window and returns cheap statistics about it. Used by the NoiseCheck
     * before any encode happens, and by the Prober to choose its windows.
     */
    public suspend fun analyseWindows(
        source: StorageRef,
        windows: List<FrameWindow>,
    ): AnalysisResult

    /**
     * Encodes [windows] only, at [setting]. This is the unit the search runs on: at 720p
     * with every-5th-frame subsampling, so it is cheap enough to binary-search.
     */
    public suspend fun encodeWindows(
        source: StorageRef,
        setting: EncodeSetting,
        windows: List<FrameWindow>,
    ): WindowEncodeResult

    /**
     * The full-file encode: decoder Surface wired to encoder Surface, audio stream-copied,
     * colour range preserved, 2 s keyframes, front-index MP4, KEY_PRIORITY = 1.
     *
     * [onProgress] receives a fraction in 0..1 as often as the encoder emits; throttling
     * to 2 Hz happens further up (frontend-architecture §9).
     */
    public suspend fun encodeFull(
        source: StorageRef,
        setting: EncodeSetting,
        destination: TempRef,
        onProgress: (Double) -> Unit,
    ): FullEncodeResult
}

/** A half-open window of the source, in milliseconds. */
public data class FrameWindow(
    val startMs: Long,
    val durationMs: Long,
) {
    init {
        require(startMs >= 0) { "startMs must not be negative" }
        require(durationMs > 0) { "durationMs must be positive" }
    }

    public val endMs: Long get() = startMs + durationMs
}

public sealed interface AnalysisResult {
    /**
     * [highFrequencyEnergy] is the noise estimate the NoiseCheck thresholds on: 0.0 is a
     * clean synthetic gradient, 1.0 is pure sensor grain.
     */
    public data class Analysed(
        val highFrequencyEnergy: Double,
        val colorRange: ColorRange,
    ) : AnalysisResult {
        init {
            require(highFrequencyEnergy in 0.0..1.0) {
                "highFrequencyEnergy $highFrequencyEnergy out of range 0..1"
            }
        }
    }

    public data class Failed(val error: CodecError) : AnalysisResult
}

public sealed interface WindowEncodeResult {
    /**
     * [handle] identifies the encoded sample so the Scorer can be handed it without the
     * bytes travelling through Kotlin. [bytes] is what the sample cost, which is how the
     * Prober extrapolates a whole-file size.
     */
    public data class Encoded(
        val handle: EncodedSample,
        val bytes: Long,
    ) : WindowEncodeResult

    public data class Failed(val error: CodecError) : WindowEncodeResult
}

public sealed interface FullEncodeResult {
    public data class Encoded(
        val bytes: Long,
        val durationMs: Long,
        val colorRange: ColorRange,
    ) : FullEncodeResult

    public data class Failed(val error: CodecError) : FullEncodeResult
}

/** An opaque reference to an encoded window living in the platform layer. */
@JvmInline
public value class EncodedSample(public val value: String) {
    init {
        require(value.isNotBlank()) { "EncodedSample must not be blank" }
    }
}

/**
 * Everything the codec can do wrong. [CodecReclaimed] is a retryable interruption
 * (app-architecture §10) — priority 1 means Android takes the encoder back for any
 * foreground app, and the runner catches, waits, and resumes.
 */
public sealed interface CodecError {
    /** The OS took the encoder away. Wait and resume from the last sync point. */
    public data class CodecReclaimed(val atFraction: Double) : CodecError

    /** No hardware encoder for this profile. There is no software fallback by design. */
    public data class NoHardwareSupport(val detail: String) : CodecError

    /** The source changed underneath the encode. */
    public data object SourceChanged : CodecError

    public data class OutOfSpace(val neededBytes: Long, val availableBytes: Long) : CodecError

    public data class Fatal(val detail: String) : CodecError
}
