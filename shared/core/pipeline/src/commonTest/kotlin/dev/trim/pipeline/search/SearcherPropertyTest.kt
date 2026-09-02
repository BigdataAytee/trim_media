package dev.trim.pipeline.search

import dev.trim.model.Bracket
import dev.trim.model.ColorRange
import dev.trim.model.EncodeSetting
import dev.trim.model.OutputCodec
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.xpsnr
import dev.trim.ports.FrameWindow
import dev.trim.ports.fake.ContentModel
import dev.trim.ports.fake.FakeWorld
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The search's one guarantee, stated as a property rather than as examples:
 *
 * > for any scored content, the result is the **most aggressive setting whose score is at
 * > least the target**.
 *
 * The content is generated — random monotone score curves, random brackets, random targets
 * — and the expected answer is computed by exhaustive scan of the bracket, which is the
 * definition the binary search is an optimisation of. Anything the search does that a
 * linear scan would not is a bug this test catches.
 */
class SearcherPropertyTest {

    private data class Case(
        val bracket: Bracket,
        val scores: List<Double>,
        val target: Double,
        val prediction: EncodeSetting?,
    ) {
        fun scoreAt(quality: Int): Double = scores[quality]

        /** The definition: a linear scan for the last setting that still clears the target. */
        fun expectedSetting(): EncodeSetting? =
            (bracket.safest.quality..bracket.mostAggressive.quality)
                .lastOrNull { scoreAt(it) >= target }
                ?.let { EncodeSetting(it, OutputCodec.HEVC) }
    }

    private val cases = arbitrary { source ->
        val random = source.random
        val lo = random.nextInt(10, 26)
        val hi = (lo + random.nextInt(1, 22)).coerceAtMost(EncodeSetting.MAX_QUALITY)

        // A monotonically non-increasing curve over the whole quality axis. Some steps are
        // zero, so plateaux — several settings scoring identically — are covered too.
        var value = random.nextDouble(40.0, 70.0).coerceAtMost(100.0)
        val scores = MutableList(EncodeSetting.MAX_QUALITY + 1) { 0.0 }
        for (q in 0..EncodeSetting.MAX_QUALITY) {
            scores[q] = value
            value = (value - random.nextDouble(0.0, 2.5)).coerceAtLeast(0.0)
        }

        // Targets drawn across the whole interesting range: above the safest end (nothing
        // reachable), inside the curve, and below the aggressive end (everything reachable).
        val target = when (random.nextInt(4)) {
            0 -> scores[lo] + random.nextDouble(0.0, 5.0)
            1 -> scores[hi] - random.nextDouble(0.0, 5.0)
            else -> scores[random.nextInt(lo, hi + 1)]
        }.coerceIn(0.0, 100.0)

        val prediction = when (random.nextInt(3)) {
            0 -> EncodeSetting(random.nextInt(lo, hi + 1), OutputCodec.HEVC)
            else -> null
        }

        Case(
            bracket = Bracket(
                EncodeSetting(lo, OutputCodec.HEVC),
                EncodeSetting(hi, OutputCodec.HEVC),
            ),
            scores = scores,
            target = target,
            prediction = prediction,
        )
    }

    @Test
    fun `a cold search finds the most aggressive setting that clears the target`() = runTest {
        checkAll(ITERATIONS, cases) { case ->
            val world = worldFor(case)
            val result = Searcher(world.codec, world.scorer).search(
                source = SOURCE,
                fingerprint = FINGERPRINT,
                bracket = case.bracket,
                windows = WINDOWS,
                targetXpsnr = xpsnr(case.target),
                prediction = null,
            )

            when (val expected = case.expectedSetting()) {
                null -> {
                    assertIs<SearchResult.NoSettingReachesTarget>(result)
                    assertEquals(case.bracket.safest, result.safest)
                }
                else -> {
                    assertIs<SearchResult.Found>(result)
                    assertEquals(
                        expected,
                        result.setting,
                        "target=${case.target} bracket=${case.bracket.safest.quality}.." +
                            "${case.bracket.mostAggressive.quality} " +
                            "prediction=${case.prediction?.quality}",
                    )
                    assertTrue(
                        result.score.value >= case.target,
                        "the chosen setting must actually clear the target",
                    )
                }
            }
        }
    }

    /**
     * The prediction path trades optimality for probes on purpose (DECISIONS D4.5): one
     * confirming probe, and the prediction is taken if it holds. So it does not promise the
     * *optimum* — but it must still promise **safety**: never a setting that misses the
     * target, and never more aggressive than the optimum.
     */
    @Test
    fun `a predicted search is never unsafe, even when it is not optimal`() = runTest {
        checkAll(ITERATIONS, cases) { case ->
            val prediction = case.prediction ?: return@checkAll
            val world = worldFor(case)
            val result = Searcher(world.codec, world.scorer).search(
                source = SOURCE,
                fingerprint = FINGERPRINT,
                bracket = case.bracket,
                windows = WINDOWS,
                targetXpsnr = xpsnr(case.target),
                prediction = prediction,
            )
            val optimum = case.expectedSetting()
            when (result) {
                is SearchResult.Found -> {
                    assertTrue(
                        result.score.value >= case.target,
                        "a predicted search returned a setting that misses the target",
                    )
                    assertTrue(
                        optimum != null && result.setting.quality <= optimum.quality,
                        "a predicted search returned ${result.setting.quality}, which is " +
                            "more aggressive than the optimum ${optimum?.quality}",
                    )
                }
                is SearchResult.NoSettingReachesTarget -> assertEquals(null, optimum)
                is SearchResult.Failed -> error("the fake world cannot fail here")
            }
        }
    }

    @Test
    fun `the search never costs more than a linear scan would`() = runTest {
        checkAll(ITERATIONS, cases) { case ->
            val world = worldFor(case)
            val result = Searcher(world.codec, world.scorer).search(
                source = SOURCE,
                fingerprint = FINGERPRINT,
                bracket = case.bracket,
                windows = WINDOWS,
                targetXpsnr = xpsnr(case.target),
                prediction = case.prediction,
            )
            val probes = when (result) {
                is SearchResult.Found -> result.probes
                is SearchResult.NoSettingReachesTarget -> result.probes
                is SearchResult.Failed -> 0
            }
            // One probe to establish the safest end, then a binary search over the rest —
            // and a confirming probe first when a prediction was offered.
            val budget = 2 + ceil(ln(case.bracket.size.toDouble() + 1) / ln(2.0)).toInt()
            assertTrue(
                probes <= budget,
                "used $probes probes for a bracket of ${case.bracket.size}; budget $budget",
            )
        }
    }

    private fun worldFor(case: Case): FakeWorld {
        val world = FakeWorld()
        world.addVideo(
            SOURCE,
            content = ContentModel(
                noiseEnergy = 0.1,
                ceilingVmaf = 99.0,
                ceilingXpsnr = 70.0,
                colorRange = ColorRange.LIMITED,
                xpsnrAt = { q -> case.scoreAt(q) },
                vmafAt = { q -> (99.0 - q * 0.2).coerceIn(0.0, 100.0) },
                sizeFractionAt = { q -> (0.9 - q * 0.015).coerceAtLeast(0.05) },
            ),
        )
        return world
    }

    private companion object {
        const val ITERATIONS = 400
        val SOURCE = StorageRef("content://property")
        val FINGERPRINT = SourceFingerprint(1_000, 1_000, "hash")
        val WINDOWS = listOf(FrameWindow(0, 2_000))
    }
}
