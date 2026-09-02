package dev.trim.ports.fake

import dev.trim.model.ColorRange
import dev.trim.model.EncodeSetting
import dev.trim.model.Metric
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.ports.EncodedSample

/**
 * A synthetic description of what a file *is like*, from the point of view of the two
 * ports that have opinions about content: the codec and the scorer.
 *
 * The score functions must be monotonically non-increasing in the quality index. That is
 * a physical property of every rate-control mode and the precondition the binary search
 * relies on (DECISIONS D4.3); [ContentModel] checks it on construction so a test cannot
 * accidentally prove the search wrong by handing it impossible content.
 */
public class ContentModel(
    public val noiseEnergy: Double,
    public val ceilingVmaf: Double,
    public val ceilingXpsnr: Double,
    public val colorRange: ColorRange,
    private val xpsnrAt: (Int) -> Double,
    private val vmafAt: (Int) -> Double,
    private val sizeFractionAt: (Int) -> Double,
) {
    init {
        require(noiseEnergy in 0.0..1.0) { "noiseEnergy out of range 0..1" }
        checkMonotone("xpsnr", xpsnrAt)
        checkMonotone("vmaf", vmafAt)
        checkMonotone("sizeFraction", sizeFractionAt)
    }

    public fun scoreAt(setting: EncodeSetting, metric: Metric): Double = when (metric) {
        Metric.XPSNR -> xpsnrAt(setting.quality)
        Metric.VMAF -> vmafAt(setting.quality)
    }

    public fun ceiling(metric: Metric): Double = when (metric) {
        Metric.XPSNR -> ceilingXpsnr
        Metric.VMAF -> ceilingVmaf
    }

    /** Fraction of the source's size this setting produces. */
    public fun sizeFraction(setting: EncodeSetting): Double = sizeFractionAt(setting.quality)

    private fun checkMonotone(name: String, f: (Int) -> Double) {
        var previous = f(EncodeSetting.MIN_QUALITY)
        for (q in (EncodeSetting.MIN_QUALITY + 1)..EncodeSetting.MAX_QUALITY) {
            val current = f(q)
            require(current <= previous + TOLERANCE) {
                "$name is not monotonically non-increasing in quality: " +
                    "f(${q - 1})=$previous, f($q)=$current. The search's precondition " +
                    "(DECISIONS D4.3) forbids this content."
            }
            previous = current
        }
    }

    public companion object {
        private const val TOLERANCE = 1e-9

        /**
         * The everyday shape: quality falls off linearly with the index and so does size.
         * [xpsnrAt20]/[vmafAt20] anchor the curves at quality 20.
         */
        public fun linear(
            noiseEnergy: Double = 0.15,
            xpsnrAt20: Double = 44.0,
            xpsnrPerStep: Double = 0.55,
            vmafAt20: Double = 98.0,
            vmafPerStep: Double = 0.45,
            ceilingVmaf: Double = 99.5,
            ceilingXpsnr: Double = 52.0,
            sizeFractionAt20: Double = 0.62,
            sizeFractionPerStep: Double = 0.022,
            colorRange: ColorRange = ColorRange.LIMITED,
        ): ContentModel = ContentModel(
            noiseEnergy = noiseEnergy,
            ceilingVmaf = ceilingVmaf,
            ceilingXpsnr = ceilingXpsnr,
            colorRange = colorRange,
            xpsnrAt = { q -> (xpsnrAt20 - (q - 20) * xpsnrPerStep).coerceIn(0.0, 100.0) },
            vmafAt = { q -> (vmafAt20 - (q - 20) * vmafPerStep).coerceIn(0.0, 100.0) },
            sizeFractionAt = { q ->
                (sizeFractionAt20 - (q - 20) * sizeFractionPerStep).coerceIn(0.02, 1.5)
            },
        )

        /** Content that no setting can shrink acceptably — every score sits under target. */
        public fun stubborn(noiseEnergy: Double = 0.2): ContentModel = linear(
            noiseEnergy = noiseEnergy,
            xpsnrAt20 = 33.0,
            vmafAt20 = 88.0,
            ceilingVmaf = 99.0,
            sizeFractionAt20 = 0.95,
            sizeFractionPerStep = 0.004,
        )

        /** Grainy content the NoiseCheck rejects before any encode happens. */
        public fun noisy(): ContentModel = linear(noiseEnergy = 0.86)

        /** Content whose own ceiling is below target + margin: the HeadroomCheck rejects it. */
        public fun ceilingBound(): ContentModel = linear(
            ceilingVmaf = 93.0,
            ceilingXpsnr = 34.0,
            vmafAt20 = 92.0,
            xpsnrAt20 = 33.5,
        )
    }
}

/** What the scorer needs to know about a sample the codec produced. */
public data class SampleInfo(
    val source: StorageRef,
    val setting: EncodeSetting,
)

/**
 * The shared world the codec and scorer fakes agree about. Keeping it in one object is
 * what stops the two fakes drifting into an incoherent story (a sample the scorer has
 * never heard of, a score that does not match the setting that produced it).
 */
public class FakeContentLibrary {
    private val models = mutableMapOf<StorageRef, ContentModel>()
    private val samples = mutableMapOf<String, SampleInfo>()
    private val tempEncodes = mutableMapOf<String, SampleInfo>()
    private var nextSample = 0

    public fun register(ref: StorageRef, model: ContentModel) {
        models[ref] = model
    }

    public fun model(ref: StorageRef): ContentModel =
        models[ref] ?: error("no ContentModel registered for $ref — the fake world is incomplete")

    public fun hasModel(ref: StorageRef): Boolean = ref in models

    public fun mintSample(source: StorageRef, setting: EncodeSetting): EncodedSample {
        val handle = EncodedSample("sample-${nextSample++}")
        samples[handle.value] = SampleInfo(source, setting)
        return handle
    }

    public fun sample(handle: EncodedSample): SampleInfo? = samples[handle.value]

    /** What the codec wrote into scratch space, so the Verifier's scorer can read it back. */
    public fun recordTempEncode(temp: TempRef, source: StorageRef, setting: EncodeSetting) {
        tempEncodes[temp.value] = SampleInfo(source, setting)
    }

    public fun tempEncode(temp: TempRef): SampleInfo? = tempEncodes[temp.value]
}
