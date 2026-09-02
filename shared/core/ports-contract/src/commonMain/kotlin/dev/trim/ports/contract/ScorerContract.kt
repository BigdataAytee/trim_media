package dev.trim.ports.contract

import dev.trim.model.EncodeSetting
import dev.trim.model.Metric
import dev.trim.model.StorageRef
import dev.trim.ports.EncodedSample
import dev.trim.ports.FrameWindow
import dev.trim.ports.ScoreRequest
import dev.trim.ports.ScoreResult
import dev.trim.ports.Scorer
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The other half of the search's precondition. [CodecContract] requires that aggression
 * does not increase size; this requires that it does not increase *score*.
 *
 * Together they are what makes the binary search legal rather than a heuristic: the
 * settings clearing a target form a prefix of the bracket, so the answer is the last
 * element of that prefix and can be found in a logarithm of probes. An implementation that
 * fails the monotonicity clause makes the Searcher unsound, and the failure says so.
 */
public abstract class ScorerContract {

    public interface Fixture : PortFixture {
        public val scorer: Scorer
        public suspend fun source(): StorageRef
        public suspend fun windows(): List<FrameWindow>

        /** An encoded sample of [source] at [setting], as the Codec would produce. */
        public suspend fun sampleAt(setting: EncodeSetting): EncodedSample

        /** A handle the scorer has never seen. */
        public suspend fun unknownSample(): EncodedSample
    }

    public abstract fun createFixture(): Fixture

    public fun cases(): List<ContractCase> = listOf(
        case("a score comes back on the metric it was asked for") {
            withFixture { f ->
                for (metric in Metric.entries) {
                    val result = f.scorer.score(request(f, f.sampleAt(SAFE), metric))
                    assertIs<ScoreResult.Scored>(result)
                    assertEquals(
                        metric,
                        result.score.metric,
                        "asked for $metric and got ${result.score.metric}; the calibration " +
                            "table would be applied to the wrong scale",
                    )
                    assertTrue(result.score.value in 0.0..100.0)
                }
            }
        },
        case("a more aggressive setting never scores higher") {
            withFixture { f ->
                val safe = f.scorer.score(request(f, f.sampleAt(SAFE), Metric.XPSNR))
                val aggressive = f.scorer.score(request(f, f.sampleAt(AGGRESSIVE), Metric.XPSNR))
                assertIs<ScoreResult.Scored>(safe)
                assertIs<ScoreResult.Scored>(aggressive)
                assertTrue(
                    aggressive.score.value <= safe.score.value + SCORE_TOLERANCE,
                    "quality ${AGGRESSIVE.quality} scored ${aggressive.score.value} and " +
                        "quality ${SAFE.quality} scored ${safe.score.value}. Score must not " +
                        "rise with aggression: the Searcher's binary search is unsound on " +
                        "this implementation (DECISIONS D4.3)",
                )
            }
        },
        case("scoring the same sample twice gives the same answer") {
            withFixture { f ->
                val sample = f.sampleAt(SAFE)
                val first = f.scorer.score(request(f, sample, Metric.XPSNR))
                val second = f.scorer.score(request(f, sample, Metric.XPSNR))
                assertIs<ScoreResult.Scored>(first)
                assertIs<ScoreResult.Scored>(second)
                assertEquals(
                    first.score.value,
                    second.score.value,
                    absoluteTolerance = SCORE_TOLERANCE,
                    message = "a search that gets different answers to the same question " +
                        "does not converge",
                )
            }
        },
        case("the source's ceiling is at least anything an encode of it achieves") {
            withFixture { f ->
                val ceiling = f.scorer.ceiling(f.source(), Metric.VMAF)
                assertIs<ScoreResult.Scored>(ceiling)
                val achieved = f.scorer.score(request(f, f.sampleAt(SAFE), Metric.VMAF))
                assertIs<ScoreResult.Scored>(achieved)
                assertTrue(
                    ceiling.score.value >= achieved.score.value - SCORE_TOLERANCE,
                    "an encode scored ${achieved.score.value}, above the source's own " +
                        "ceiling of ${ceiling.score.value}. The HeadroomCheck would then " +
                        "skip files that were compressible after all",
                )
            }
        },
        case("a sample the scorer has never seen fails rather than scoring") {
            withFixture { f ->
                val result = f.scorer.score(request(f, f.unknownSample(), Metric.XPSNR))
                assertIs<ScoreResult.Failed>(
                    result,
                    "an unknown sample scored anyway; a search could be driven by a number " +
                        "belonging to no encode",
                )
            }
        },
    )

    /** The request the Searcher would make: the fixture's own source and windows. */
    private suspend fun request(
        fixture: Fixture,
        sample: EncodedSample,
        metric: Metric,
    ) = ScoreRequest(
        source = fixture.source(),
        encoded = sample,
        windows = fixture.windows(),
        metric = metric,
        subsampleEveryNthFrame = if (metric == Metric.XPSNR) SEARCH_SUBSAMPLE else VERIFY_SUBSAMPLE,
        normalisedWidth = if (metric == Metric.XPSNR) SEARCH_WIDTH else VERIFY_WIDTH,
    )

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = createFixture()
        try {
            block(fixture)
        } finally {
            fixture.tearDown()
        }
    }

    private companion object {
        val SAFE = EncodeSetting(20, dev.trim.model.OutputCodec.HEVC)
        val AGGRESSIVE = EncodeSetting(32, dev.trim.model.OutputCodec.HEVC)
        const val SCORE_TOLERANCE = 0.01

        /** app-architecture §9: every 5th frame at 720p searching, every 3rd at 1920 verifying. */
        const val SEARCH_SUBSAMPLE = 5
        const val VERIFY_SUBSAMPLE = 3
        const val SEARCH_WIDTH = 1280
        const val VERIFY_WIDTH = 1920
    }
}
