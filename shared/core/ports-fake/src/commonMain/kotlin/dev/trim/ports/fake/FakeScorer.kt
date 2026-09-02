package dev.trim.ports.fake

import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.StorageRef
import dev.trim.ports.ScoreRequest
import dev.trim.ports.ScoreResult
import dev.trim.ports.Scorer

/**
 * Scores read straight off the [ContentModel] that produced the sample, so the scorer and
 * the codec can never disagree about what a setting did.
 */
public class FakeScorer(
    private val library: FakeContentLibrary,
    private val clock: FakeClock = FakeClock(),
) : Scorer {

    public data class ScoreCall(val source: StorageRef, val metric: Metric, val quality: Int)

    public val calls: MutableList<ScoreCall> = mutableListOf()
    public val ceilingCalls: MutableList<StorageRef> = mutableListOf()

    public var delayMs: Long = 0

    private var failure: Pair<String, Int>? = null
    private var ceilingFailure: String? = null

    public fun failScores(detail: String = "injected scorer failure", times: Int = 1) {
        failure = detail to times
    }

    public fun failCeiling(detail: String = "injected ceiling failure") {
        ceilingFailure = detail
    }

    override suspend fun score(request: ScoreRequest): ScoreResult {
        if (delayMs > 0) clock.sleep(delayMs)
        failure?.let { (detail, times) ->
            if (times > 0) {
                failure = detail to (times - 1)
                return ScoreResult.Failed(detail)
            }
        }
        val sample = library.sample(request.encoded)
            ?: return ScoreResult.Failed("unknown sample ${request.encoded}")
        calls += ScoreCall(sample.source, request.metric, sample.setting.quality)
        val model = library.model(sample.source)
        return ScoreResult.Scored(
            QualityScore(request.metric, model.scoreAt(sample.setting, request.metric)),
        )
    }

    override suspend fun scoreFile(request: dev.trim.ports.FileScoreRequest): ScoreResult {
        if (delayMs > 0) clock.sleep(delayMs)
        failure?.let { (detail, times) ->
            if (times > 0) {
                failure = detail to (times - 1)
                return ScoreResult.Failed(detail)
            }
        }
        val encode = library.tempEncode(request.encoded)
            ?: return ScoreResult.Failed("nothing was encoded to ${request.encoded}")
        calls += ScoreCall(encode.source, request.metric, encode.setting.quality)
        val model = library.model(encode.source)
        return ScoreResult.Scored(
            QualityScore(request.metric, model.scoreAt(encode.setting, request.metric)),
        )
    }

    override suspend fun ceiling(source: StorageRef, metric: Metric): ScoreResult {
        if (delayMs > 0) clock.sleep(delayMs)
        ceilingCalls += source
        ceilingFailure?.let { return ScoreResult.Failed(it) }
        if (!library.hasModel(source)) return ScoreResult.Failed("unknown source $source")
        return ScoreResult.Scored(QualityScore(metric, library.model(source).ceiling(metric)))
    }
}
