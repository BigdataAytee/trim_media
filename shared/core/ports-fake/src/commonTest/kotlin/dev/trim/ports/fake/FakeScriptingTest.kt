package dev.trim.ports.fake

import dev.trim.model.EncodeSetting
import dev.trim.model.Metric
import dev.trim.model.OutputCodec
import dev.trim.model.StorageRef
import dev.trim.ports.AnalysisResult
import dev.trim.ports.CodecError
import dev.trim.ports.FrameWindow
import dev.trim.ports.FullEncodeResult
import dev.trim.ports.MoveResult
import dev.trim.ports.OriginalDestination
import dev.trim.ports.ScoreRequest
import dev.trim.ports.ScoreResult
import dev.trim.ports.StorageWriteResult
import dev.trim.ports.ThermalReading
import dev.trim.ports.WindowEncodeResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The fakes are evidence only if they can be scripted into every row of the error taxonomy
 * (app-architecture §10). These tests prove each script actually fires.
 */
class FakeScriptingTest {

    private val window = listOf(FrameWindow(0, 2_000))

    @Test
    fun `content whose score rises with aggression cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> {
            ContentModel(
                noiseEnergy = 0.1,
                ceilingVmaf = 99.0,
                ceilingXpsnr = 50.0,
                colorRange = dev.trim.model.ColorRange.LIMITED,
                xpsnrAt = { q -> 30.0 + q },
                vmafAt = { 95.0 },
                sizeFractionAt = { 0.5 },
            )
        }
    }

    @Test
    fun `a codec reclaim interrupts the encode and the next attempt succeeds`() = runTest {
        val world = FakeWorld()
        val ref = world.addVideo(StorageRef("content://a"))
        world.codec.reclaimDuringFullEncode(times = 1, atFraction = 0.5)
        val temp = world.storage.createTemp("a")

        val first = world.codec.encodeFull(ref, EncodeSetting(24, OutputCodec.HEVC), temp) {}
        assertIs<FullEncodeResult.Failed>(first)
        assertIs<CodecError.CodecReclaimed>(first.error)

        val second = world.codec.encodeFull(ref, EncodeSetting(24, OutputCodec.HEVC), temp) {}
        assertIs<FullEncodeResult.Encoded>(second)
        assertEquals(2, world.codec.fullEncodes.size)
    }

    @Test
    fun `the source can change underneath a running encode`() = runTest {
        val world = FakeWorld()
        val ref = world.addVideo(StorageRef("content://a"))
        val before = world.storage.fingerprint(ref)
        world.codec.changeSourceDuringFullEncode(atFraction = 0.4)
        val temp = world.storage.createTemp("a")

        val result = world.codec.encodeFull(ref, EncodeSetting(24, OutputCodec.HEVC), temp) {}
        assertIs<FullEncodeResult.Failed>(result)
        assertEquals(CodecError.SourceChanged, result.error)
        assertTrue(world.storage.fingerprint(ref) != before)
    }

    @Test
    fun `storage failures can be injected at any single operation`() = runTest {
        val world = FakeWorld()
        val ref = world.addVideo(StorageRef("content://a"))
        world.storage.failOn(StorageOp.MOVE_ORIGINAL, detail = "volume busy")

        val failed = world.storage.moveOriginal(ref, OriginalDestination.UndoBin)
        assertIs<MoveResult.Failed>(failed)
        assertEquals("volume busy", failed.detail)

        // The injection was one-shot: the file is still where it was, and the retry works.
        assertTrue(world.storage.exists(ref))
        assertIs<MoveResult.Moved>(world.storage.moveOriginal(ref, OriginalDestination.UndoBin))
    }

    @Test
    fun `a failure can be scheduled after n successful calls`() = runTest {
        val world = FakeWorld()
        val a = world.addVideo(StorageRef("content://a"))
        val b = world.addVideo(StorageRef("content://b"))
        world.storage.failOn(StorageOp.TRIGGER_MEDIA_SCAN, skip = 1)

        assertEquals(StorageWriteResult.Written, world.storage.triggerMediaScan(a))
        assertIs<StorageWriteResult.Failed>(world.storage.triggerMediaScan(b))
    }

    @Test
    fun `a snapshot restores byte-for-byte comparison of the world`() = runTest {
        val world = FakeWorld()
        val ref = world.addVideo(StorageRef("content://a"))
        val before = world.storage.snapshot()

        world.storage.moveOriginal(ref, OriginalDestination.UndoBin)
        assertTrue(world.storage.snapshot() != before)

        world.storage.moveBack(StorageRef("bin://a"), ref)
        assertEquals(before, world.storage.snapshot())
    }

    @Test
    fun `thermal can oscillate across the pause threshold`() = runTest {
        val thermal = FakeThermal.oscillating(cycles = 4)
        val readings = (1..4).map { thermal.read() }
        val headroom = readings.map { (it as ThermalReading.Headroom).headroomConsumed }
        assertEquals(listOf(0.85, 0.35, 0.85, 0.35), headroom)
    }

    @Test
    fun `an unsupported headroom API reports coarse status rather than a bogus zero`() =
        runTest {
            val reading = FakeThermal.unsupportedApi().read()
            assertIs<ThermalReading.CoarseOnly>(reading)
        }

    @Test
    fun `scorer and codec agree about what a setting did`() = runTest {
        val world = FakeWorld()
        val ref = world.addVideo(StorageRef("content://a"))
        val setting = EncodeSetting(26, OutputCodec.HEVC)

        val encoded = world.codec.encodeWindows(ref, setting, window)
        assertIs<WindowEncodeResult.Encoded>(encoded)

        val scored = world.scorer.score(
            ScoreRequest(ref, encoded.handle, window, Metric.XPSNR, 5, 1920),
        )
        assertIs<ScoreResult.Scored>(scored)
        assertEquals(
            world.library.model(ref).scoreAt(setting, Metric.XPSNR),
            scored.score.value,
            absoluteTolerance = 1e-9,
        )
    }

    @Test
    fun `noisy content is visible to the analyser before any encode`() = runTest {
        val world = FakeWorld()
        val ref = world.addVideo(StorageRef("content://a"), content = ContentModel.noisy())
        val analysis = world.codec.analyseWindows(ref, window)
        assertIs<AnalysisResult.Analysed>(analysis)
        assertTrue(analysis.highFrequencyEnergy > 0.8)
        assertTrue(world.codec.windowEncodes.isEmpty(), "no encode may precede the noise check")
    }

    @Test
    fun `the clock is virtual and records every sleep`() = runTest {
        val clock = FakeClock(startEpochMs = 1_000)
        clock.sleep(60_000)
        clock.sleep(500)
        assertEquals(61_500, clock.nowEpochMs())
        assertEquals(listOf(60_000L, 500L), clock.sleeps)
    }
}
