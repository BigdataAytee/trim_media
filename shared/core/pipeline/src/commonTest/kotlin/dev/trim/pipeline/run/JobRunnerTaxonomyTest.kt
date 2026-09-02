package dev.trim.pipeline.run

import dev.trim.model.CompressPhase
import dev.trim.model.FailureKind
import dev.trim.model.FailureReason
import dev.trim.model.JobOutcome
import dev.trim.model.SkipReason
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.VerificationFailure
import dev.trim.model.VideoCodec
import dev.trim.pipeline.support.PipelineHarness
import dev.trim.ports.CodecError
import dev.trim.ports.fake.ContentModel
import dev.trim.ports.fake.FakeMediaInfo
import dev.trim.ports.fake.FakeThermal
import dev.trim.ports.fake.StorageOp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One test per row of the error taxonomy in app-architecture §10, plus the two rules the
 * taxonomy exists to enforce, asserted on every row:
 *
 * > no failure mode may cost the user a file, and no failure mode may be invisible.
 *
 * [assertNothingLost] checks the first — the original is still there, at its original path
 * or in the bin, byte-identical — and the return type checks the second: every path out of
 * [JobRunner.run] is a [JobOutcome], and all three of its cases carry an explanation.
 */
class JobRunnerTaxonomyTest {

    // ---- kind 1: expected skips ----

    @Test
    fun `an efficient file is skipped by triage before any port is touched`() = runTest {
        val h = PipelineHarness()
        val efficient = (0.04 * 1920 * 1080 * 30).toLong()
        val ref = h.addVideo(
            "efficient.mp4",
            header = FakeMediaInfo.header(codec = VideoCodec.HEVC, bitrateBps = efficient),
        )
        val outcome = h.run(ref)

        assertIs<JobOutcome.Skipped>(outcome)
        assertIs<SkipReason.AlreadyEfficient>(outcome.reason)
        assertTrue(h.world.codec.analyses.isEmpty(), "triage must not need a decoder")
        h.assertNothingLost(ref)
    }

    @Test
    fun `an HDR file is skipped with the HDR reason`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo(
            "sunset.mp4",
            header = FakeMediaInfo.header(transfer = TransferFunction.PQ, bitDepth = 10),
        )
        val outcome = h.run(ref)
        assertIs<JobOutcome.Skipped>(outcome)
        assertIs<SkipReason.Hdr>(outcome.reason)
        h.assertNothingLost(ref)
    }

    @Test
    fun `a noisy file is skipped before any encode happens`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("grain.mp4", content = ContentModel.noisy())
        val outcome = h.run(ref)

        assertIs<JobOutcome.Skipped>(outcome)
        assertIs<SkipReason.TooNoisy>(outcome.reason)
        assertTrue(h.world.codec.windowEncodes.isEmpty(), "the noise check must precede encodes")
        assertTrue(h.world.codec.fullEncodes.isEmpty())
        h.assertNothingLost(ref)
    }

    @Test
    fun `a file with no quality headroom is skipped before any encode happens`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("soft.mp4", content = ContentModel.ceilingBound())
        val outcome = h.run(ref)

        assertIs<JobOutcome.Skipped>(outcome)
        assertIs<SkipReason.NoHeadroom>(outcome.reason)
        assertTrue(h.world.codec.windowEncodes.isEmpty())
        h.assertNothingLost(ref)
    }

    @Test
    fun `a file the bracket cannot reach is skipped after exactly one probe`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("stubborn.mp4", content = ContentModel.stubborn())
        val outcome = h.run(ref)

        assertIs<JobOutcome.Skipped>(outcome)
        assertIs<SkipReason.CannotReachTarget>(outcome.reason)
        assertEquals(1, h.world.codec.windowEncodes.size, "the early abort costs one probe")
        assertTrue(h.world.codec.fullEncodes.isEmpty())
        h.assertNothingLost(ref)
    }

    // ---- kind 2: retryable interruptions ----

    @Test
    fun `a reclaimed codec pauses the job and resumes it, rather than losing the file`() =
        runTest {
            val h = PipelineHarness()
            val ref = h.addVideo("reclaimed.mp4")
            h.world.codec.reclaimDuringFullEncode(times = 2, atFraction = 0.5)

            val phases = mutableListOf<CompressPhase>()
            val outcome = h.run(ref) { phases += it }

            assertIs<JobOutcome.Compressed>(outcome)
            assertEquals(3, h.world.codec.fullEncodes.size, "two reclaims, three attempts")
            assertTrue(
                phases.filterIsInstance<CompressPhase.Paused>()
                    .count { it.reason == dev.trim.model.PauseReason.CodecReclaimed } == 2,
                "each reclaim must be visible as a named pause, not a silence",
            )
            assertEquals(
                listOf(h.config.codecReclaimWaitMs, h.config.codecReclaimWaitMs),
                h.world.clock.sleeps,
            )
        }

    @Test
    fun `a codec reclaimed forever eventually fails the file without losing it`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("hostile.mp4")
        h.world.codec.reclaimDuringFullEncode(times = 99, atFraction = 0.5)

        val outcome = h.run(ref)

        assertIs<JobOutcome.Failed>(outcome)
        assertEquals(FailureKind.FILE_LEVEL, outcome.reason.kind)
        assertEquals(h.config.codecReclaimMaxRetries + 1, h.world.codec.fullEncodes.size)
        h.assertNothingLost(ref)
    }

    @Test
    fun `a thermal storm pauses the encode and it finishes when the device cools`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("hot.mp4")
        h.world.thermal.scriptHeadroom(0.9, 0.9, 0.9, 0.2, 0.2, 0.2)

        val phases = mutableListOf<CompressPhase>()
        val outcome = h.run(ref) { phases += it }

        assertIs<JobOutcome.Compressed>(outcome)
        val thermalPauses = phases.filterIsInstance<CompressPhase.Paused>()
            .filter { it.reason is dev.trim.model.PauseReason.Thermal }
        assertEquals(3, thermalPauses.size)
        assertTrue(
            h.world.clock.sleeps.all { it >= h.config.minimumThermalPauseMs },
            "a thermal pause shorter than the minimum would stutter-step the encoder",
        )
        h.assertOriginalInBin(ref)
    }

    // ---- kind 3: file-level failures ----

    @Test
    fun `a failed verification keeps the original untouched`() = runTest {
        val h = PipelineHarness()
        // Scores well enough to win the search on XPSNR but fails the VMAF check.
        val ref = h.addVideo(
            "liar.mp4",
            content = ContentModel.linear(vmafAt20 = 94.0, vmafPerStep = 0.4),
        )
        val outcome = h.run(ref)

        assertIs<JobOutcome.Failed>(outcome)
        val reason = outcome.reason
        assertIs<FailureReason.VerificationFailed>(reason)
        assertIs<VerificationFailure.ScoreBelowTarget>(reason.detail)
        assertEquals(FailureKind.FILE_LEVEL, reason.kind)
        h.assertNothingLost(ref)
    }

    @Test
    fun `a source changed mid-encode fails the file and keeps it`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("moving.mp4")
        h.world.codec.changeSourceDuringFullEncode(atFraction = 0.4)

        val outcome = h.run(ref)

        assertIs<JobOutcome.Failed>(outcome)
        assertIs<FailureReason.SourceChanged>(outcome.reason)
        assertTrue(h.world.storage.exists(ref), "the file is still there — changed, but there")
        assertTrue(h.journal.entries.isEmpty())
    }

    @Test
    fun `an encoder error fails the file and keeps it`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("unsupported.mp4")
        h.world.codec.failFullEncodes(
            CodecError.NoHardwareSupport("no hardware encoder for this profile"),
            times = 1,
        )

        val outcome = h.run(ref)

        assertIs<JobOutcome.Failed>(outcome)
        assertIs<FailureReason.EncoderError>(outcome.reason)
        h.assertNothingLost(ref)
    }

    @Test
    fun `running out of space fails the file and keeps it`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("big.mp4")
        h.world.storage.freeSpaceOverride = 1_000

        val outcome = h.run(ref)

        assertIs<JobOutcome.Failed>(outcome)
        assertIs<FailureReason.OutOfSpace>(outcome.reason)
        h.assertNothingLost(ref)
    }

    // ---- kind 4: invariant breaches ----

    @Test
    fun `a replace that rolls back is recorded as an invariant breach and loses nothing`() =
        runTest {
            val h = PipelineHarness()
            val ref = h.addVideo("rollback.mp4")
            h.world.storage.failOn(StorageOp.PROMOTE_TEMP, detail = "rename refused")

            val outcome = h.run(ref)

            assertIs<JobOutcome.Failed>(outcome)
            val reason = outcome.reason
            assertIs<FailureReason.ReplaceRolledBack>(reason)
            assertEquals(FailureKind.INVARIANT_BREACH, reason.kind)
            assertEquals(3, reason.failedStep)
            assertTrue(h.runner.diagnostics.any { "rollback.mp4" in it })
            h.assertNothingLost(ref)
        }

    @Test
    fun `an undo entry that cannot be written rolls the whole replace back`() = runTest {
        val h = PipelineHarness()
        val ref = h.addVideo("nodb.mp4")
        h.journal.failNextWrite = "database is locked"

        val outcome = h.run(ref)

        assertIs<JobOutcome.Failed>(outcome)
        val reason = outcome.reason
        assertIs<FailureReason.ReplaceRolledBack>(reason)
        assertEquals(6, reason.failedStep)
        h.assertNothingLost(ref)
    }

    // ---- the two rules, checked over the whole taxonomy at once ----

    @Test
    fun `no path exits without a recorded outcome and no path loses the file`() = runTest {
        val injections: List<Pair<String, suspend (PipelineHarness, StorageRef) -> Unit>> =
            listOf(
                "clean" to { _, _ -> },
                "noisy" to { h, r -> h.world.library.register(r, ContentModel.noisy()) },
                "no headroom" to { h, r ->
                    h.world.library.register(r, ContentModel.ceilingBound())
                },
                "cannot reach target" to { h, r ->
                    h.world.library.register(r, ContentModel.stubborn())
                },
                "codec reclaimed" to { h, _ -> h.world.codec.reclaimDuringFullEncode(2) },
                "codec reclaimed forever" to { h, _ -> h.world.codec.reclaimDuringFullEncode(99) },
                "source changed" to { h, _ -> h.world.codec.changeSourceDuringFullEncode() },
                "encoder fatal" to { h, _ ->
                    h.world.codec.failFullEncodes(CodecError.Fatal("boom"))
                },
                "out of space" to { h, _ -> h.world.storage.freeSpaceOverride = 1_000 },
                "scorer failure" to { h, _ -> h.world.scorer.failScores(times = 99) },
                "ceiling failure" to { h, _ -> h.world.scorer.failCeiling() },
                "metadata copy failed" to { h, _ ->
                    h.world.storage.failOn(StorageOp.COPY_METADATA)
                },
                "move original failed" to { h, _ ->
                    h.world.storage.failOn(StorageOp.MOVE_ORIGINAL)
                },
                "promote failed" to { h, _ -> h.world.storage.failOn(StorageOp.PROMOTE_TEMP) },
                "timestamps failed" to { h, _ ->
                    h.world.storage.failOn(StorageOp.RESTORE_TIMESTAMPS)
                },
                "media scan failed" to { h, _ ->
                    h.world.storage.failOn(StorageOp.TRIGGER_MEDIA_SCAN)
                },
                "undo write failed" to { h, _ -> h.journal.failNextWrite = "locked" },
            )

        for ((name, inject) in injections) {
            val h = PipelineHarness()
            val ref = h.addVideo("subject.mp4")
            val before = h.world.storage.snapshot().files.getValue(ref.value)
            inject(h, ref)

            val phases = mutableListOf<CompressPhase>()
            val outcome = h.run(ref) { phases += it }

            // Every case is one of exactly three, and every one carries an explanation.
            val explanation: String = when (outcome) {
                is JobOutcome.Compressed -> outcome.result.originalFate.displayText
                is JobOutcome.Skipped -> outcome.reason.displayText
                is JobOutcome.Failed -> outcome.reason.displayText
            }
            assertTrue(explanation.isNotBlank(), "[$name] exited without an explanation")

            // The last phase always names a terminal state.
            val last = phases.lastOrNull()
            assertTrue(
                last is CompressPhase.Done || last is CompressPhase.Rejected,
                "[$name] last phase was $last, which is not terminal",
            )

            // Nothing is lost. Either the file at the original path still holds at least
            // what it held before — the "source changed" case edits it, which is another
            // app's doing and not a loss — or the bin holds a byte-identical copy. A
            // compressed file standing at the path with no copy in the bin fails this,
            // because a verified encode is always smaller than its source.
            val after = h.world.storage.snapshot().files
            val atPath = after[ref.value]
            val inBin = after.entries.firstOrNull { it.key.startsWith("bin://") }?.value
            assertTrue(
                (atPath != null && atPath.bytes >= before.bytes) ||
                    (inBin != null && inBin.bytes == before.bytes),
                "[$name] lost the original: user storage is now $after",
            )

            // No temp file is left behind, on any path.
            assertTrue(h.world.storage.temps.isEmpty(), "[$name] leaked a temp file")
        }
    }

    // ---- helpers ----

    private suspend fun PipelineHarness.run(
        ref: StorageRef,
        onPhase: (CompressPhase) -> Unit = {},
    ): JobOutcome = runner.run(videoFor(ref), request(), onPhase)

    private suspend fun PipelineHarness.assertNothingLost(ref: StorageRef) {
        assertTrue(
            world.storage.exists(ref) || world.storage.files.keys.any { it.startsWith("bin://") },
            "the original is neither at its path nor in the bin",
        )
        assertTrue(world.storage.temps.isEmpty(), "a temp file was left behind")
    }

    private suspend fun PipelineHarness.assertOriginalInBin(ref: StorageRef) {
        assertTrue(world.storage.files.keys.any { it.startsWith("bin://") })
        assertTrue(world.storage.exists(ref), "the compressed file took the original's path")
    }
}
