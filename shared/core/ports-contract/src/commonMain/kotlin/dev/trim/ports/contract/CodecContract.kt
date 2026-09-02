package dev.trim.ports.contract

import dev.trim.model.EncodeSetting
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.ports.AnalysisResult
import dev.trim.ports.Codec
import dev.trim.ports.FrameWindow
import dev.trim.ports.FullEncodeResult
import dev.trim.ports.Storage
import dev.trim.ports.WindowEncodeResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Most of this suite is ordinary hygiene. One clause is not.
 *
 * **"A more aggressive setting never produces a larger encode"** is the monotonicity the
 * binary search rests on (DECISIONS D4.3). Until this suite existed it was an assumption
 * about how rate control behaves; here it becomes a property every Codec implementation is
 * checked against. If a device's encoder violates it, the Searcher is unsound on that
 * device — and this clause is how that is discovered, rather than as a user's file coming
 * back bigger than it went in.
 *
 * The clause is stated with a tolerance because container overhead and keyframe placement
 * make encode sizes jittery at adjacent settings; it compares the ends of the bracket,
 * where a real difference must show.
 */
public abstract class CodecContract {

    public interface Fixture : PortFixture {
        public val codec: Codec
        public val storage: Storage

        /** A source the codec can decode, of known duration. */
        public suspend fun source(): StorageRef
        public suspend fun sourceDurationMs(): Long

        /** A ref that names nothing. */
        public suspend fun missingSource(): StorageRef

        /** Windows the implementation is happy to decode from [source]. */
        public suspend fun windows(): List<FrameWindow>

        public suspend fun createTemp(): TempRef
    }

    public abstract fun createFixture(): Fixture

    public fun cases(): List<ContractCase> = listOf(
        case("analysis reports noise on a scale the NoiseCheck can threshold") {
            withFixture { f ->
                val result = f.codec.analyseWindows(f.source(), f.windows())
                assertIs<AnalysisResult.Analysed>(result)
                assertTrue(
                    result.highFrequencyEnergy in 0.0..1.0,
                    "high-frequency energy ${result.highFrequencyEnergy} is outside 0..1, " +
                        "so the noise threshold means nothing",
                )
            }
        },
        case("a window encode yields a handle and a positive size") {
            withFixture { f ->
                val result = f.codec.encodeWindows(f.source(), SAFE, f.windows())
                assertIs<WindowEncodeResult.Encoded>(result)
                assertTrue(result.bytes > 0, "a window encode produced ${result.bytes} bytes")
            }
        },
        case("a more aggressive setting never produces a larger encode") {
            withFixture { f ->
                val safe = f.codec.encodeWindows(f.source(), SAFE, f.windows())
                val aggressive = f.codec.encodeWindows(f.source(), AGGRESSIVE, f.windows())
                assertIs<WindowEncodeResult.Encoded>(safe)
                assertIs<WindowEncodeResult.Encoded>(aggressive)
                assertTrue(
                    aggressive.bytes <= safe.bytes * SIZE_TOLERANCE,
                    "quality ${AGGRESSIVE.quality} produced ${aggressive.bytes} bytes and " +
                        "quality ${SAFE.quality} produced ${safe.bytes}. Size must not rise " +
                        "with aggression: the Searcher's binary search is unsound on this " +
                        "implementation (DECISIONS D4.3)",
                )
            }
        },
        case("the same source and setting encode to the same size twice") {
            withFixture { f ->
                // Not determinism of the bytes — hardware encoders are free to differ —
                // but of the *size*, because the Prober extrapolates a whole-file estimate
                // from one window encode and shows it to the user as a range.
                val first = f.codec.encodeWindows(f.source(), SAFE, f.windows())
                val second = f.codec.encodeWindows(f.source(), SAFE, f.windows())
                assertIs<WindowEncodeResult.Encoded>(first)
                assertIs<WindowEncodeResult.Encoded>(second)
                val drift = (first.bytes - second.bytes).toDouble() / first.bytes
                assertTrue(
                    kotlin.math.abs(drift) <= REPEAT_TOLERANCE,
                    "the same encode twice differed by ${drift * 100}%, so the probe's " +
                        "estimate is not reproducible",
                )
            }
        },
        case("a full encode reports progress from zero to one, never backwards") {
            withFixture { f ->
                val temp = f.createTemp()
                val progress = mutableListOf<Double>()
                val result = f.codec.encodeFull(f.source(), SAFE, temp) { progress += it }
                assertIs<FullEncodeResult.Encoded>(result)
                assertTrue(progress.isNotEmpty(), "a full encode reported no progress at all")
                assertTrue(
                    progress.all { it in 0.0..1.0 },
                    "progress left 0..1: ${progress.filterNot { it in 0.0..1.0 }}",
                )
                assertTrue(
                    progress.zipWithNext().all { (a, b) -> b >= a },
                    "progress went backwards, which the UI renders as a stalling bar",
                )
                assertEquals(
                    1.0,
                    progress.last(),
                    absoluteTolerance = 1e-9,
                    message = "a completed encode must end at 1.0",
                )
                f.storage.deleteTemp(temp)
            }
        },
        case("a full encode preserves the source's duration") {
            withFixture { f ->
                val temp = f.createTemp()
                val result = f.codec.encodeFull(f.source(), SAFE, temp) {}
                assertIs<FullEncodeResult.Encoded>(result)
                assertEquals(
                    f.sourceDurationMs(),
                    result.durationMs,
                    "the encode changed the video's length; the Verifier rejects that, so " +
                        "an implementation that does it can never complete a job",
                )
                assertTrue(result.bytes > 0)
                f.storage.deleteTemp(temp)
            }
        },
        case("a full encode leaves its bytes in the temp it was given") {
            withFixture { f ->
                val temp = f.createTemp()
                val result = f.codec.encodeFull(f.source(), SAFE, temp) {}
                assertIs<FullEncodeResult.Encoded>(result)
                assertEquals(
                    result.bytes,
                    f.storage.tempSizeBytes(temp),
                    "the codec reported a size the storage port cannot see; the Replacer " +
                        "promotes what storage has, not what the codec claims",
                )
                f.storage.deleteTemp(temp)
            }
        },
        case("a missing source fails with a named error rather than throwing") {
            withFixture { f ->
                val missing = f.missingSource()
                assertIs<WindowEncodeResult.Failed>(
                    f.codec.encodeWindows(missing, SAFE, f.windows()),
                )
                val temp = f.createTemp()
                assertIs<FullEncodeResult.Failed>(f.codec.encodeFull(missing, SAFE, temp) {})
                f.storage.deleteTemp(temp)
            }
        },
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

        /** Container overhead makes adjacent settings jittery; the bracket ends must not be. */
        const val SIZE_TOLERANCE = 1.02

        /** Two runs of the same encode may differ by this much and still be reproducible. */
        const val REPEAT_TOLERANCE = 0.02
    }
}
