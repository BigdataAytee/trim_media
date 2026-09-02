package dev.trim.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests exist to prove that the dishonest states are *unrepresentable*, not merely
 * discouraged. Each one is an attempt to construct something the UI must never be able to
 * render, and each one must fail.
 */
class HonestTypesTest {

    @Test
    fun `an estimate cannot be inverted`() {
        assertFailsWith<IllegalArgumentException> {
            EstimateRange(lowBytes = 100, highBytes = 99, confidence = EstimateConfidence.SEED)
        }
    }

    @Test
    fun `a total estimate takes the weakest confidence in the set`() {
        val probed = EstimateRange(10, 12, EstimateConfidence.PROBED)
        val seeded = EstimateRange(20, 30, EstimateConfidence.SEED)
        val total = listOf(probed, seeded).sumEstimates()
        assertEquals(30, total.lowBytes)
        assertEquals(42, total.highBytes)
        assertEquals(EstimateConfidence.SEED, total.confidence)
    }

    @Test
    fun `an estimate band is symmetric and clamped at zero`() {
        val wide = EstimateRange.around(100, 2.0, EstimateConfidence.SEED)
        assertEquals(0, wide.lowBytes)
        assertEquals(300, wide.highBytes)
    }

    @Test
    fun `scores of different metrics refuse to be compared`() {
        assertFailsWith<IllegalArgumentException> {
            @Suppress("UNUSED_EXPRESSION")
            xpsnr(40.0) > vmaf(95.0)
        }
    }

    @Test
    fun `a score outside zero to one hundred does not exist`() {
        assertFailsWith<IllegalArgumentException> { vmaf(101.0) }
        assertFailsWith<IllegalArgumentException> { xpsnr(-0.1) }
    }

    @Test
    fun `a result that is not smaller is not a result`() {
        assertFailsWith<IllegalArgumentException> {
            CompressionResult(
                videoId = VideoId("v"),
                originalBytes = 100,
                compressedBytes = 100,
                setting = EncodeSetting(24, OutputCodec.HEVC),
                verifiedScore = vmaf(95.0),
                originalFate = OriginalFate.Deleted,
            )
        }
    }

    @Test
    fun `keeping an original for zero days is Deleted, not KeptDays`() {
        assertFailsWith<IllegalArgumentException> { OriginalFate.KeptDays(0) }
        assertEquals("deleted", OriginalFate.Deleted.displayText)
    }

    @Test
    fun `every skip reason carries plain-language text`() {
        val reasons: List<SkipReason> = listOf(
            SkipReason.AlreadyEfficient(VideoCodec.HEVC, 0.04),
            SkipReason.TooNoisy(0.9),
            SkipReason.Hdr(TransferFunction.PQ, 10),
            SkipReason.SecondaryTrack(1, 2, 0),
            SkipReason.TooSmall(1_000, 8L * 1024 * 1024),
            SkipReason.NoHeadroom(vmaf(94.0), vmaf(97.0)),
            SkipReason.CannotReachTarget(vmaf(92.0), vmaf(95.5)),
        )
        reasons.forEach { reason ->
            assertTrue(reason.displayText.isNotBlank(), "$reason has no display text")
            assertTrue(
                reason.displayText.none { it.isDigit() && reason !is SkipReason.Hdr } ||
                    reason is SkipReason.TooSmall,
                "$reason leaks a code into its display text",
            )
            assertTrue('!' !in reason.displayText, "no exclamation marks in system copy")
        }
    }

    @Test
    fun `progress phases are never anonymous`() {
        val id = VideoId("v")
        val phases: List<CompressPhase> = listOf(
            CompressPhase.Checking(id),
            CompressPhase.FindingSetting(id, probesDone = 2),
            CompressPhase.Encoding(id, fractionComplete = 0.5, etaSeconds = 30),
            CompressPhase.Verifying(id),
            CompressPhase.Paused(id, PauseReason.CodecReclaimed),
            CompressPhase.Rejected(id, SkipReason.TooNoisy(0.8)),
        )
        // Exhaustive `when` with no else: adding a phase without naming it will not compile.
        phases.forEach { phase ->
            val name: String = when (phase) {
                is CompressPhase.Checking -> "checking"
                is CompressPhase.FindingSetting -> "finding setting"
                is CompressPhase.Encoding -> "encoding"
                is CompressPhase.Verifying -> "verifying"
                is CompressPhase.Paused -> phase.reason.displayText
                is CompressPhase.Done -> "done"
                is CompressPhase.Rejected -> phase.reason.displayText
            }
            assertTrue(name.isNotBlank())
        }
    }

    @Test
    fun `encoding progress outside zero to one does not exist`() {
        assertFailsWith<IllegalArgumentException> {
            CompressPhase.Encoding(VideoId("v"), fractionComplete = 1.1, etaSeconds = null)
        }
    }

    @Test
    fun `a bracket cannot be inverted and knows its sub-bracket`() {
        val bracket = Bracket(
            safest = EncodeSetting(20, OutputCodec.HEVC),
            mostAggressive = EncodeSetting(32, OutputCodec.HEVC),
        )
        assertEquals(13, bracket.size)
        assertEquals(EncodeSetting(23, OutputCodec.HEVC), bracket.below(EncodeSetting(24, OutputCodec.HEVC))!!.mostAggressive)
        assertNull(bracket.below(EncodeSetting(20, OutputCodec.HEVC)))
        assertFailsWith<IllegalArgumentException> {
            Bracket(EncodeSetting(32, OutputCodec.HEVC), EncodeSetting(20, OutputCodec.HEVC))
        }
    }

    @Test
    fun `a job is running exactly when it holds a claim`() {
        assertFailsWith<IllegalArgumentException> {
            Job(JobId("j"), VideoId("v"), JobTrigger.NIGHTLY, JobState.RUNNING, 0, 0, null)
        }
        assertFailsWith<IllegalArgumentException> {
            Job(JobId("j"), VideoId("v"), JobTrigger.NIGHTLY, JobState.QUEUED, 0, 0, "token")
        }
    }

    @Test
    fun `a nightly cap of zero is not a way to disable the nightly run`() {
        assertFailsWith<IllegalArgumentException> {
            TrimSettings.DEFAULT.copy(nightlyByteCap = 0)
        }
    }
}
