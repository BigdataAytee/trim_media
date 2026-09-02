package dev.trim.pipeline.triage

import dev.trim.model.EncodeSetting
import dev.trim.model.EstimateConfidence
import dev.trim.model.OutputCodec
import dev.trim.model.SkipReason
import dev.trim.model.TransferFunction
import dev.trim.model.TriageResult
import dev.trim.model.VideoCodec
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.predict.InMemoryPredictor
import dev.trim.pipeline.predict.Prediction
import dev.trim.pipeline.predict.PredictionKey
import dev.trim.pipeline.support.video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriageTest {

    private val triage = Triage()

    @Test
    fun `a bloated H264 clip is a candidate`() {
        val result = triage.judge(video(bitrateBps = 20_000_000))
        assertIs<TriageResult.Accepted>(result)
        assertTrue(result.candidate.estimate.highBytes < result.candidate.video.sizeBytes)
    }

    @Test
    fun `an efficient HEVC clip is already efficient`() {
        // 0.04 bpp at 1080p30 — below the 0.050 HEVC threshold.
        val bitrate = (0.04 * 1920 * 1080 * 30).toLong()
        val result = triage.judge(video(codec = VideoCodec.HEVC, bitrateBps = bitrate))
        assertIs<TriageResult.Rejected>(result)
        assertIs<SkipReason.AlreadyEfficient>(result.reason)
    }

    @Test
    fun `bitrate is judged per pixel per second, not as flat Mbps`() {
        // Same 20 Mbps. At 1080p30 it is bloated; at 4K60 it is thrifty. A flat-Mbps rule
        // would reach the same verdict for both, which is the bug this rule exists to avoid.
        val hd = triage.judge(video(id = "hd", width = 1920, height = 1080, frameRate = 30.0))
        val uhd = triage.judge(
            video(id = "uhd", width = 3840, height = 2160, frameRate = 60.0),
        )
        assertIs<TriageResult.Accepted>(hd)
        assertIs<TriageResult.Rejected>(uhd)
        assertIs<SkipReason.AlreadyEfficient>(uhd.reason)
    }

    @Test
    fun `the bar is lower for H264 than for HEVC, because a codec generation is free`() {
        // The same 0.06 bits per pixel per second. As H.264 it is worth converting: moving
        // to HEVC harvests a codec generation before any quality is traded. As HEVC there
        // is no such gain left, so the same number is not enough to justify the work.
        val bitrate = (0.06 * 1920 * 1080 * 30).toLong()
        assertIs<TriageResult.Accepted>(triage.judge(video(bitrateBps = bitrate)))
        assertIs<TriageResult.Rejected>(
            triage.judge(video(codec = VideoCodec.HEVC, bitrateBps = bitrate)),
        )
    }

    @Test
    fun `HDR is left untouched`() {
        assertRejectedWith<SkipReason.Hdr>(video(transfer = TransferFunction.PQ))
        assertRejectedWith<SkipReason.Hdr>(video(transfer = TransferFunction.HLG))
        assertRejectedWith<SkipReason.Hdr>(video(bitDepth = 10))
    }

    @Test
    fun `extra tracks are rejected before anything else is considered`() {
        // This clip is also HDR, also tiny, also efficient. The structural reason wins,
        // because it is the one that explains the most to the user (DECISIONS D3.4).
        val awkward = video(
            audioTracks = 2,
            transfer = TransferFunction.PQ,
            sizeBytes = 1_000,
            bitrateBps = 1_000,
        )
        assertRejectedWith<SkipReason.SecondaryTrack>(awkward)
    }

    @Test
    fun `HDR outranks size and efficiency`() {
        assertRejectedWith<SkipReason.Hdr>(
            video(transfer = TransferFunction.PQ, sizeBytes = 1_000, bitrateBps = 1_000),
        )
    }

    @Test
    fun `a file below the minimum size is not worth the pipeline`() {
        assertRejectedWith<SkipReason.TooSmall>(video(sizeBytes = 4L * 1024 * 1024))
    }

    @Test
    fun `a candidate that promises no real saving is already efficient`() {
        // Above the bpp threshold, but the estimate lands within 15% of the source size.
        val config = PipelineConfig(
            efficiencyThresholds = mapOf(VideoCodec.H264 to 0.0, VideoCodec.UNKNOWN to 0.0),
            expectedSizeFractions = mapOf(VideoCodec.H264 to 0.95, VideoCodec.UNKNOWN to 0.95),
            targetBitsPerPixelPerSecond = 0.30,
        )
        val result = Triage(config).judge(video(bitrateBps = 20_000_000))
        assertIs<TriageResult.Rejected>(result)
        assertIs<SkipReason.AlreadyEfficient>(result.reason)
    }

    @Test
    fun `estimates are seed confidence until the predictor has something to say`() {
        val result = triage.judge(video())
        assertIs<TriageResult.Accepted>(result)
        assertEquals(EstimateConfidence.SEED, result.candidate.estimate.confidence)
        assertNull(result.candidate.predictedSetting)
    }

    @Test
    fun `a predictor with enough observations narrows the band and suggests a setting`() {
        val subject = video()
        val key = PredictionKey.of(subject, "generic")
        val predictor = InMemoryPredictor(
            seeds = mapOf(key to Prediction(EncodeSetting(26, OutputCodec.HEVC), 0.45, 9)),
        )
        val result = Triage(PipelineConfig(), predictor).judge(subject)
        assertIs<TriageResult.Accepted>(result)
        assertEquals(EstimateConfidence.PREDICTED, result.candidate.estimate.confidence)
        assertEquals(EncodeSetting(26, OutputCodec.HEVC), result.candidate.predictedSetting)

        // Narrower *relative to what it is estimating*: the two estimates centre on
        // different sizes, so comparing raw widths would compare two different questions.
        fun relativeWidth(range: dev.trim.model.EstimateRange): Double =
            (range.highBytes - range.lowBytes).toDouble() / range.midpointBytes
        val seed = (triage.judge(subject) as TriageResult.Accepted).candidate.estimate
        assertTrue(
            relativeWidth(result.candidate.estimate) < relativeWidth(seed),
            "a predicted estimate must be narrower than a seed one",
        )
    }

    @Test
    fun `a predicted setting outside the bracket is ignored rather than trusted`() {
        val subject = video()
        val key = PredictionKey.of(subject, "generic")
        val predictor = InMemoryPredictor(
            seeds = mapOf(key to Prediction(EncodeSetting(45, OutputCodec.HEVC), 0.2, 9)),
        )
        val result = Triage(PipelineConfig(), predictor).judge(subject)
        assertIs<TriageResult.Accepted>(result)
        assertNull(result.candidate.predictedSetting)
    }

    @Test
    fun `the bracket comes from the source codec and HEVC starts below 18`() {
        val h264 = triage.judge(video()) as TriageResult.Accepted
        assertEquals(20, h264.candidate.bracket.safest.quality)

        val hevcBitrate = (0.09 * 1920 * 1080 * 30).toLong()
        val hevc = triage.judge(
            video(codec = VideoCodec.HEVC, bitrateBps = hevcBitrate),
        ) as TriageResult.Accepted
        assertEquals(16, hevc.candidate.bracket.safest.quality)
    }

    private inline fun <reified T : SkipReason> assertRejectedWith(
        subject: dev.trim.model.Video,
    ) {
        val result = triage.judge(subject)
        assertIs<TriageResult.Rejected>(result)
        assertIs<T>(result.reason)
    }
}
