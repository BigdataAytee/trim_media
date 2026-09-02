package dev.trim.data

import dev.trim.data.db.TrimDatabase
import dev.trim.model.EncodeSetting
import dev.trim.model.OutputCodec
import dev.trim.pipeline.predict.Prediction
import dev.trim.pipeline.predict.PredictionKey
import dev.trim.pipeline.predict.Predictor

/**
 * The durable predictor. Ships with a per-device seed table (app-architecture §3) and
 * learns from every completed file; a seeded row is replaced by observation the moment
 * this device has one of its own.
 */
public class PredictorStore(
    private val db: TrimDatabase,
) : Predictor {

    override fun suggest(key: PredictionKey): Prediction? {
        val row = db.predictorQueries.select(
            device_class = key.deviceClass,
            camera_tag = key.cameraTag ?: NO_CAMERA,
            codec = key.codec.name,
            width_bucket = key.widthBucket.toLong(),
            fps_bucket = key.fpsBucket.toLong(),
            bitrate_bucket = key.bitrateBucket.toLong(),
        ).executeAsOneOrNull() ?: return null

        if (row.observations <= 0) return null
        return Prediction(
            setting = EncodeSetting(
                quality = (row.quality_sum / row.observations).toInt(),
                outputCodec = OutputCodec.HEVC,
            ),
            sizeFraction = row.fraction_sum / row.observations,
            observations = row.observations.toInt(),
        )
    }

    override fun observe(key: PredictionKey, setting: EncodeSetting, sizeFraction: Double) {
        db.transaction {
            val camera = key.cameraTag ?: NO_CAMERA
            val existing = db.predictorQueries.select(
                device_class = key.deviceClass,
                camera_tag = camera,
                codec = key.codec.name,
                width_bucket = key.widthBucket.toLong(),
                fps_bucket = key.fpsBucket.toLong(),
                bitrate_bucket = key.bitrateBucket.toLong(),
            ).executeAsOneOrNull()

            // A seed is a starting point, not an observation: the first real measurement
            // replaces it outright rather than being averaged with a guess.
            val base = existing?.takeIf { it.is_seed == 0L }
            db.predictorQueries.upsert(
                dev.trim.data.db.Predictor(
                    device_class = key.deviceClass,
                    camera_tag = camera,
                    codec = key.codec.name,
                    width_bucket = key.widthBucket.toLong(),
                    fps_bucket = key.fpsBucket.toLong(),
                    bitrate_bucket = key.bitrateBucket.toLong(),
                    quality_sum = (base?.quality_sum ?: 0) + setting.quality,
                    fraction_sum = (base?.fraction_sum ?: 0.0) + sizeFraction,
                    observations = (base?.observations ?: 0) + 1,
                    is_seed = 0,
                ),
            )
        }
    }

    /** Installs the shipped seed table. Seeds never overwrite an observation. */
    public fun installSeeds(seeds: Map<PredictionKey, Prediction>) {
        db.transaction {
            for ((key, prediction) in seeds) {
                val camera = key.cameraTag ?: NO_CAMERA
                val existing = db.predictorQueries.select(
                    device_class = key.deviceClass,
                    camera_tag = camera,
                    codec = key.codec.name,
                    width_bucket = key.widthBucket.toLong(),
                    fps_bucket = key.fpsBucket.toLong(),
                    bitrate_bucket = key.bitrateBucket.toLong(),
                ).executeAsOneOrNull()
                if (existing != null && existing.is_seed == 0L) continue
                db.predictorQueries.upsert(
                    dev.trim.data.db.Predictor(
                        device_class = key.deviceClass,
                        camera_tag = camera,
                        codec = key.codec.name,
                        width_bucket = key.widthBucket.toLong(),
                        fps_bucket = key.fpsBucket.toLong(),
                        bitrate_bucket = key.bitrateBucket.toLong(),
                        quality_sum = prediction.setting.quality.toLong() *
                            prediction.observations.coerceAtLeast(1),
                        fraction_sum = prediction.sizeFraction *
                            prediction.observations.coerceAtLeast(1),
                        observations = prediction.observations.coerceAtLeast(1).toLong(),
                        is_seed = 1,
                    ),
                )
            }
        }
    }

    private companion object {
        /** The primary key has no nullable columns, so "no camera tag" needs a value. */
        const val NO_CAMERA = "-"
    }
}
