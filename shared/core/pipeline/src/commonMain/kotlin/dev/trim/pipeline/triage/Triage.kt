package dev.trim.pipeline.triage

import dev.trim.model.Candidate
import dev.trim.model.EstimateConfidence
import dev.trim.model.EstimateRange
import dev.trim.model.SkipReason
import dev.trim.model.TriageResult
import dev.trim.model.Video
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.predict.PredictionKey
import dev.trim.pipeline.predict.Predictor

/**
 * The cheapest stage and the one that does the most work: a pure judgment on header facts,
 * with no ports and no I/O, deciding whether a file is worth the pipeline's time.
 *
 * The rule that matters (app-architecture §3): **bitrate is judged per pixel per second,
 * never as flat Mbps**. A flat threshold punishes 4K60 for being 4K60 and lets a bloated
 * 720p clip through; bits per pixel per second is the only measure that compares them.
 *
 * Gate order is structural-before-quality (DECISIONS D3.4): a file with extra audio tracks
 * is told *that*, not that it is already efficient, because the more explanatory reason is
 * the more useful one in the "can't be shrunk" list.
 */
public class Triage(
    private val config: PipelineConfig = PipelineConfig(),
    private val predictor: Predictor? = null,
    private val deviceClass: String = "generic",
) {

    public fun judge(video: Video): TriageResult {
        if (video.hasSecondaryTrack) {
            return reject(
                video,
                SkipReason.SecondaryTrack(
                    videoTracks = video.videoTrackCount,
                    audioTracks = video.audioTrackCount,
                    otherTracks = video.otherTrackCount,
                ),
            )
        }
        if (video.isHdr) {
            return reject(video, SkipReason.Hdr(video.transfer, video.bitDepth))
        }
        if (video.sizeBytes < config.minimumSizeBytes) {
            return reject(video, SkipReason.TooSmall(video.sizeBytes, config.minimumSizeBytes))
        }

        val threshold = config.efficiencyThreshold(video.codec)
        if (video.bitsPerPixelPerSecond <= threshold) {
            return reject(
                video,
                SkipReason.AlreadyEfficient(video.codec, video.bitsPerPixelPerSecond),
            )
        }

        val prediction = predictor?.suggest(PredictionKey.of(video, deviceClass))
        val estimate = estimate(video, prediction?.sizeFraction, prediction?.confidence)

        // A candidate that does not promise a real saving is not a candidate. This second
        // gate exists because the bpp threshold and the estimate are two different views of
        // the same question, and the honest one is "how much smaller, actually".
        val mustBeUnder = (video.sizeBytes * (1.0 - config.minimumSavingFraction)).toLong()
        if (estimate.highBytes >= mustBeUnder) {
            return reject(
                video,
                SkipReason.AlreadyEfficient(video.codec, video.bitsPerPixelPerSecond),
            )
        }

        val bracket = config.bracketFor(video.codec)
        val predictedSetting = prediction?.setting?.takeIf { bracket.contains(it) }
        return TriageResult.Accepted(
            Candidate(
                video = video,
                estimate = estimate,
                bracket = bracket,
                predictedSetting = predictedSetting,
            ),
        )
    }

    /**
     * The pre-probe estimate: what the target bits-per-pixel-per-second would cost for this
     * file's pixel rate and duration, as a band. A band, never a number — there is no
     * single-number estimate type before a probe has run (frontend-architecture §4.2).
     */
    private fun estimate(
        video: Video,
        predictedFraction: Double?,
        predictedConfidence: EstimateConfidence?,
    ): EstimateRange {
        // Two seed views of the same question, and each is the right one in a different
        // regime: "what our target bits-per-pixel-per-second would cost" governs a wildly
        // bloated file, while "what re-encoding this codec typically costs" governs a
        // mildly bloated one, where the target bitrate view would barely promise anything.
        // Whichever is smaller is the one that regime is in.
        val fraction = predictedFraction ?: run {
            val fromTargetBitrate =
                config.targetBitsPerPixelPerSecond / video.bitsPerPixelPerSecond
            val fromCodec = config.expectedSizeFraction(video.codec)
            minOf(fromTargetBitrate, fromCodec).coerceIn(0.02, 1.0)
        }
        val centre = (video.sizeBytes * fraction).toLong().coerceAtLeast(1L)
        val confidence = predictedConfidence ?: EstimateConfidence.SEED
        val band = when (confidence) {
            EstimateConfidence.SEED -> config.seedEstimateBand
            EstimateConfidence.PREDICTED -> config.seedEstimateBand / 2
            EstimateConfidence.PROBED -> config.seedEstimateBand / 4
        }
        return EstimateRange.around(centre, band, confidence)
    }

    private fun reject(video: Video, reason: SkipReason): TriageResult =
        TriageResult.Rejected(video, reason)
}
