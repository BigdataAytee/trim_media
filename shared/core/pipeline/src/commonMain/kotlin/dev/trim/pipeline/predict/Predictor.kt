package dev.trim.pipeline.predict

import dev.trim.model.EncodeSetting
import dev.trim.model.EstimateConfidence
import dev.trim.model.Video
import dev.trim.model.VideoCodec

/**
 * `(device, camera, codec, res, fps, bitrate-bucket) → setting` (app-architecture §3).
 * Also what makes the hub's estimates instant: the predictor answers before any encode.
 */
public interface Predictor {
    public fun suggest(key: PredictionKey): Prediction?

    /** Learns from a completed file: the setting that won and what it actually cost. */
    public fun observe(key: PredictionKey, setting: EncodeSetting, sizeFraction: Double)
}

public data class PredictionKey(
    val deviceClass: String,
    val cameraTag: String?,
    val codec: VideoCodec,
    val widthBucket: Int,
    val fpsBucket: Int,
    val bitrateBucket: Int,
) {
    public companion object {
        /** Buckets are coarse on purpose: a prediction is only useful if it generalises. */
        public fun of(video: Video, deviceClass: String, cameraTag: String? = null): PredictionKey =
            PredictionKey(
                deviceClass = deviceClass,
                cameraTag = cameraTag,
                codec = video.codec,
                widthBucket = when {
                    video.width >= 3_840 -> 3_840
                    video.width >= 2_560 -> 2_560
                    video.width >= 1_920 -> 1_920
                    video.width >= 1_280 -> 1_280
                    else -> 720
                },
                fpsBucket = when {
                    video.frameRate >= 100.0 -> 120
                    video.frameRate >= 50.0 -> 60
                    else -> 30
                },
                bitrateBucket = (video.bitsPerPixelPerSecond * 100).toInt(),
            )
    }
}

public data class Prediction(
    val setting: EncodeSetting,
    val sizeFraction: Double,
    val observations: Int,
) {
    init {
        require(sizeFraction > 0.0) { "sizeFraction must be positive" }
        require(observations >= 0) { "observations must not be negative" }
    }

    /**
     * A prediction earns [EstimateConfidence.PREDICTED] only once it has seen this device
     * do the same thing more than once; a single observation is still a seed.
     */
    public val confidence: EstimateConfidence
        get() = if (observations >= MIN_OBSERVATIONS) {
            EstimateConfidence.PREDICTED
        } else {
            EstimateConfidence.SEED
        }

    public companion object {
        public const val MIN_OBSERVATIONS: Int = 2
    }
}

/**
 * The in-memory predictor: seeds plus everything observed this process. The durable one
 * lives in core/data and shares this interface.
 */
public class InMemoryPredictor(
    seeds: Map<PredictionKey, Prediction> = emptyMap(),
) : Predictor {

    private data class Observation(val qualitySum: Int, val fractionSum: Double, val count: Int)

    private val seeds = seeds.toMutableMap()
    private val observed = mutableMapOf<PredictionKey, Observation>()

    override fun suggest(key: PredictionKey): Prediction? {
        val observation = observed[key]
        if (observation != null) {
            val setting = EncodeSetting(
                quality = observation.qualitySum / observation.count,
                outputCodec = SETTING_CODEC,
            )
            return Prediction(
                setting = setting,
                sizeFraction = observation.fractionSum / observation.count,
                observations = observation.count,
            )
        }
        return seeds[key]
    }

    override fun observe(key: PredictionKey, setting: EncodeSetting, sizeFraction: Double) {
        val previous = observed[key]
        observed[key] = if (previous == null) {
            Observation(setting.quality, sizeFraction, 1)
        } else {
            Observation(
                qualitySum = previous.qualitySum + setting.quality,
                fractionSum = previous.fractionSum + sizeFraction,
                count = previous.count + 1,
            )
        }
    }

    private companion object {
        val SETTING_CODEC = dev.trim.model.OutputCodec.HEVC
    }
}
