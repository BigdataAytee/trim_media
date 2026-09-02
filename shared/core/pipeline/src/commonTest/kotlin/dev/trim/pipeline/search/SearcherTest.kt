package dev.trim.pipeline.search

import dev.trim.model.Bracket
import dev.trim.model.ColorRange
import dev.trim.model.EncodeSetting
import dev.trim.model.Metric
import dev.trim.model.OutputCodec
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.xpsnr
import dev.trim.pipeline.PipelineConfig
import dev.trim.ports.CodecError
import dev.trim.ports.FrameWindow
import dev.trim.ports.fake.ContentModel
import dev.trim.ports.fake.FakeWorld
import kotlinx.coroutines.test.runTest
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearcherTest {

    private val windows = listOf(FrameWindow(0, 2_000))
    private val fingerprint = SourceFingerprint(1_000, 1_000, "hash")
    private val bracket = Bracket(
        EncodeSetting(20, OutputCodec.HEVC),
        EncodeSetting(32, OutputCodec.HEVC),
    )

    /** Score = 50 - quality: setting q clears a target of t exactly while q <= 50 - t. */
    private fun linearWorld(offset: Double = 50.0): FakeWorld {
        val world = FakeWorld()
        world.addVideo(
            StorageRef("content://a"),
            content = ContentModel(
                noiseEnergy = 0.1,
                ceilingVmaf = 99.0,
                ceilingXpsnr = 60.0,
                colorRange = ColorRange.LIMITED,
                xpsnrAt = { q -> (offset - q).coerceIn(0.0, 100.0) },
                vmafAt = { q -> (99.0 - q * 0.3).coerceIn(0.0, 100.0) },
                sizeFractionAt = { q -> (0.9 - q * 0.02).coerceAtLeast(0.05) },
            ),
        )
        return world
    }

    @Test
    fun `the search returns the most aggressive setting that still clears the target`() =
        runTest {
            val world = linearWorld()
            val result = Searcher(world.codec, world.scorer).search(
                source = StorageRef("content://a"),
                fingerprint = fingerprint,
                bracket = bracket,
                windows = windows,
                targetXpsnr = xpsnr(24.0),
            )
            assertIs<SearchResult.Found>(result)
            // 50 - 26 = 24 clears; 50 - 27 = 23 does not.
            assertEquals(EncodeSetting(26, OutputCodec.HEVC), result.setting)
            assertEquals(xpsnr(24.0), result.score)
        }

    @Test
    fun `a cold search costs no more than a logarithm of the bracket`() = runTest {
        val world = linearWorld()
        val result = Searcher(world.codec, world.scorer).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(24.0),
            safestScore = xpsnr(30.0),
        )
        assertIs<SearchResult.Found>(result)
        val budget = ceil(ln(bracket.size.toDouble()) / ln(2.0)).toInt()
        assertTrue(
            result.probes <= budget,
            "search used ${result.probes} probes for a bracket of ${bracket.size}; " +
                "budget is $budget",
        )
    }

    @Test
    fun `a correct prediction collapses the search to one confirming probe`() = runTest {
        val world = linearWorld()
        val result = Searcher(world.codec, world.scorer).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(24.0),
            safestScore = xpsnr(30.0),
            prediction = EncodeSetting(26, OutputCodec.HEVC),
        )
        assertIs<SearchResult.Found>(result)
        assertTrue(result.fromPrediction)
        assertEquals(1, result.probes)
        assertEquals(EncodeSetting(26, OutputCodec.HEVC), result.setting)
    }

    @Test
    fun `a prediction that overshoots falls back to the sub-bracket below it`() = runTest {
        val world = linearWorld()
        val result = Searcher(world.codec, world.scorer).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(24.0),
            safestScore = xpsnr(30.0),
            prediction = EncodeSetting(30, OutputCodec.HEVC),
        )
        assertIs<SearchResult.Found>(result)
        assertEquals(EncodeSetting(26, OutputCodec.HEVC), result.setting)
        // Nothing at or above the failed prediction is ever probed again.
        assertTrue(world.codec.windowEncodes.none { it.setting.quality > 30 })
        assertTrue(world.codec.windowEncodes.count { it.setting.quality == 30 } == 1)
    }

    @Test
    fun `a prediction that is still correct at the aggressive end is taken as-is`() = runTest {
        val world = linearWorld()
        val result = Searcher(world.codec, world.scorer).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(18.0),
            safestScore = xpsnr(30.0),
            prediction = EncodeSetting(31, OutputCodec.HEVC),
        )
        assertIs<SearchResult.Found>(result)
        assertEquals(EncodeSetting(31, OutputCodec.HEVC), result.setting)
        assertEquals(1, result.probes)
    }

    @Test
    fun `a bracket whose safest end misses the target reaches no setting`() = runTest {
        val world = linearWorld()
        val result = Searcher(world.codec, world.scorer).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(40.0),
        )
        assertIs<SearchResult.NoSettingReachesTarget>(result)
        assertEquals(EncodeSetting(20, OutputCodec.HEVC), result.safest)
        assertEquals(1, result.probes)
    }

    @Test
    fun `a codec failure mid-search is reported, not swallowed`() = runTest {
        val world = linearWorld()
        world.codec.failWindowEncodes(CodecError.Fatal("encoder went away"), times = 99)
        val result = Searcher(world.codec, world.scorer).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(24.0),
        )
        assertIs<SearchResult.Failed>(result)
    }

    @Test
    fun `the search scores at 720p with every fifth frame`() = runTest {
        val world = linearWorld()
        val config = PipelineConfig()
        Searcher(world.codec, world.scorer, config).search(
            source = StorageRef("content://a"),
            fingerprint = fingerprint,
            bracket = bracket,
            windows = windows,
            targetXpsnr = xpsnr(24.0),
        )
        assertTrue(world.scorer.calls.isNotEmpty())
        assertTrue(world.scorer.calls.all { it.metric == Metric.XPSNR })
        assertEquals(1280, config.searchNormalisedWidth)
        assertEquals(5, config.searchSubsample)
    }
}
