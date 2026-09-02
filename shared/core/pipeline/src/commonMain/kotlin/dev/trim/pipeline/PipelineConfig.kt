package dev.trim.pipeline

import dev.trim.model.Bracket
import dev.trim.model.EncodeSetting
import dev.trim.model.OutputCodec
import dev.trim.model.VideoCodec
import dev.trim.ports.FrameWindow

/**
 * Every tunable number in the pipeline, in one place, with the decision that chose it.
 *
 * The source documents state the *rules* (judge bitrate per pixel per second; the bracket
 * depends on source codec; target score + 0.5) but not the constants. Each value here is
 * recorded in docs/DECISIONS.md under D3 and D4 and is expected to be re-derived by the
 * Milestone 4 calibration corpus rather than defended on instinct.
 */
public data class PipelineConfig(
    /** DECISIONS D3.2 — below this the fixed cost of probe + search + verify wins. */
    val minimumSizeBytes: Long = 8L * 1024 * 1024,

    /** DECISIONS D3.1 — bits per pixel per second above which a codec is worth re-encoding. */
    val efficiencyThresholds: Map<VideoCodec, Double> = DEFAULT_EFFICIENCY_THRESHOLDS,

    /** DECISIONS D3.6 — the seed fraction of the source size, by source codec. */
    val expectedSizeFractions: Map<VideoCodec, Double> = DEFAULT_SIZE_FRACTIONS,

    /** The bpp the output encode is expected to land at; drives the pre-probe estimate. */
    val targetBitsPerPixelPerSecond: Double = 0.050,

    /** DECISIONS D3.5 — width of the seed estimate band, as a fraction of the midpoint. */
    val seedEstimateBand: Double = 0.18,

    /** A candidate must promise at least this much saving or it is AlreadyEfficient. */
    val minimumSavingFraction: Double = 0.15,

    /** DECISIONS D3.1 — high-frequency energy above which grain dominates. */
    val noiseThreshold: Double = 0.75,

    /** DECISIONS D4.6 — the source's own ceiling must clear target by this much. */
    val headroomMarginVmaf: Double = 2.0,

    /** app-architecture §3 — "targets score +0.5". */
    val searchTargetMargin: Double = 0.5,

    /** DECISIONS D4.1 — the search bracket, by source codec. */
    val brackets: Map<VideoCodec, Bracket> = DEFAULT_BRACKETS,

    val outputCodec: OutputCodec = OutputCodec.HEVC,

    /** §9 — every 5th frame while searching, every 3rd while verifying. */
    val searchSubsample: Int = 5,
    val verifySubsample: Int = 3,

    /** §3/§9 — the search scores at 720p; verification at the normalised 1920 width. */
    val searchNormalisedWidth: Int = 1280,
    val verifyNormalisedWidth: Int = 1920,

    /** Windows the probe and search reuse; decoded once and cached (§5). */
    val searchWindowCount: Int = 3,
    val searchWindowDurationMs: Long = 2_000,

    /** §3 — verification is tiered: one window normally, three when the margin is thin. */
    val verifyBorderlineMargin: Double = 1.0,

    /** DECISIONS D6.2 — thermal hysteresis and the minimum pause that stops stutter-stepping. */
    val thermalPauseAbove: Double = 0.7,
    val thermalResumeBelow: Double = 0.5,
    val minimumThermalPauseMs: Long = 60_000,

    /** DECISIONS D6.3 — §7's "at most every 10 s". */
    val thermalPollIntervalMs: Long = 10_000,

    /** How long to wait for a reclaimed encoder before giving up on the file. */
    val codecReclaimWaitMs: Long = 5_000,
    val codecReclaimMaxRetries: Int = 4,
) {
    init {
        require(minimumSizeBytes > 0) { "minimumSizeBytes must be positive" }
        require(seedEstimateBand >= 0.0) { "seedEstimateBand must not be negative" }
        require(minimumSavingFraction in 0.0..1.0) { "minimumSavingFraction out of range" }
        require(noiseThreshold in 0.0..1.0) { "noiseThreshold out of range" }
        require(thermalResumeBelow < thermalPauseAbove) {
            "resume threshold must be below the pause threshold or the hysteresis is a coin flip"
        }
        require(searchSubsample >= 1 && verifySubsample >= 1) { "subsample must be at least 1" }
        require(searchWindowCount >= 1) { "the search needs at least one window" }
    }

    public fun efficiencyThreshold(codec: VideoCodec): Double =
        efficiencyThresholds[codec] ?: efficiencyThresholds.getValue(VideoCodec.UNKNOWN)

    public fun expectedSizeFraction(codec: VideoCodec): Double =
        expectedSizeFractions[codec] ?: expectedSizeFractions.getValue(VideoCodec.UNKNOWN)

    public fun bracketFor(codec: VideoCodec): Bracket =
        brackets[codec] ?: brackets.getValue(VideoCodec.UNKNOWN)

    /** Windows spread evenly through a file of [durationMs], avoiding the very first frames. */
    public fun searchWindows(durationMs: Long): List<FrameWindow> {
        val usable = (durationMs - searchWindowDurationMs).coerceAtLeast(0L)
        if (usable == 0L) return listOf(FrameWindow(0, durationMs.coerceAtLeast(1)))
        return (1..searchWindowCount).map { i ->
            val start = usable * i / (searchWindowCount + 1)
            FrameWindow(start, searchWindowDurationMs)
        }
    }

    public companion object {
        /**
         * The bar is *lowest* for H.264 and highest for AV1, which is the opposite of the
         * codecs' efficiency ordering and is the point: converting an H.264 source to HEVC
         * harvests a codec generation for free, so a mildly bloated H.264 file is still
         * worth doing. An AV1 source has no codec gain left to take, so it must be
         * genuinely bloated before re-encoding it is anything but a quality trade.
         */
        public val DEFAULT_EFFICIENCY_THRESHOLDS: Map<VideoCodec, Double> = mapOf(
            VideoCodec.H264 to 0.050,
            VideoCodec.VP9 to 0.070,
            VideoCodec.HEVC to 0.085,
            VideoCodec.AV1 to 0.100,
            VideoCodec.UNKNOWN to 0.050,
        )

        /**
         * What re-encoding a source of each codec typically costs, as a fraction of the
         * original, at the default quality target. Seed values only: the predictor replaces
         * them with this device's own observations as soon as it has two.
         */
        public val DEFAULT_SIZE_FRACTIONS: Map<VideoCodec, Double> = mapOf(
            VideoCodec.H264 to 0.45,
            VideoCodec.VP9 to 0.55,
            VideoCodec.HEVC to 0.60,
            VideoCodec.AV1 to 0.70,
            VideoCodec.UNKNOWN to 0.50,
        )

        public val DEFAULT_BRACKETS: Map<VideoCodec, Bracket> = mapOf(
            VideoCodec.H264 to bracket(20, 32),
            VideoCodec.VP9 to bracket(18, 30),
            VideoCodec.AV1 to bracket(18, 30),
            // §3: "HEVC may need CRF < 18" — so the safe end starts lower.
            VideoCodec.HEVC to bracket(16, 28),
            VideoCodec.UNKNOWN to bracket(20, 32),
        )

        private fun bracket(safest: Int, mostAggressive: Int): Bracket = Bracket(
            EncodeSetting(safest, OutputCodec.HEVC),
            EncodeSetting(mostAggressive, OutputCodec.HEVC),
        )
    }
}
