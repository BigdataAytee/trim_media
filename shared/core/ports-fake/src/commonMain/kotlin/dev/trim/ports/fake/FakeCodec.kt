package dev.trim.ports.fake

import dev.trim.model.EncodeSetting
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.ports.AnalysisResult
import dev.trim.ports.Codec
import dev.trim.ports.CodecError
import dev.trim.ports.FrameWindow
import dev.trim.ports.FullEncodeResult
import dev.trim.ports.WindowEncodeResult

/**
 * A codec that does no work but tells a consistent story, and can be told to fail in
 * every way the real one can (app-architecture §10).
 *
 * The interesting scripts are [reclaimDuringFullEncode] — the OS taking the encoder back
 * mid-file, which the runner must survive by waiting and resuming — and
 * [changeSourceDuringFullEncode], which is the file moving underneath a running encode.
 */
public class FakeCodec(
    private val library: FakeContentLibrary,
    private val storage: FakeStorage,
    private val clock: FakeClock = FakeClock(),
) : Codec {

    public data class EncodeCall(val source: StorageRef, val setting: EncodeSetting)

    /** Every window encode, in order — the search's probe count is asserted from this. */
    public val windowEncodes: MutableList<EncodeCall> = mutableListOf()

    /** Every full-file encode attempt, in order, including retries after a reclaim. */
    public val fullEncodes: MutableList<EncodeCall> = mutableListOf()

    public val analyses: MutableList<StorageRef> = mutableListOf()

    public var windowEncodeDelayMs: Long = 0
    public var fullEncodeDelayMs: Long = 0

    private var analysisError: CodecError? = null
    private var windowError: Pair<CodecError, Int>? = null
    private var fullError: Pair<CodecError, Int>? = null
    private var reclaimsRemaining: Int = 0
    private var reclaimAtFraction: Double = 0.5
    private var mutateSourceAtFraction: Double? = null

    // ---- scripting ----

    public fun failAnalysis(error: CodecError) {
        analysisError = error
    }

    public fun failWindowEncodes(error: CodecError, times: Int = 1) {
        windowError = error to times
    }

    public fun failFullEncodes(error: CodecError, times: Int = 1) {
        fullError = error to times
    }

    /**
     * The OS reclaims the encoder [times] times, each at [atFraction] of the file. The
     * runner is expected to catch, wait, and resume — not to lose the file.
     */
    public fun reclaimDuringFullEncode(times: Int = 1, atFraction: Double = 0.5) {
        reclaimsRemaining = times
        reclaimAtFraction = atFraction
    }

    /** The source file is edited by another app part-way through the full encode. */
    public fun changeSourceDuringFullEncode(atFraction: Double = 0.4) {
        mutateSourceAtFraction = atFraction
    }

    // ---- port ----

    override suspend fun analyseWindows(
        source: StorageRef,
        windows: List<FrameWindow>,
    ): AnalysisResult {
        analyses += source
        analysisError?.let { return AnalysisResult.Failed(it) }
        if (!storage.exists(source)) return AnalysisResult.Failed(CodecError.SourceChanged)
        val model = library.model(source)
        return AnalysisResult.Analysed(model.noiseEnergy, model.colorRange)
    }

    override suspend fun encodeWindows(
        source: StorageRef,
        setting: EncodeSetting,
        windows: List<FrameWindow>,
    ): WindowEncodeResult {
        if (windowEncodeDelayMs > 0) clock.sleep(windowEncodeDelayMs)
        windowEncodes += EncodeCall(source, setting)
        windowError?.let { (error, times) ->
            if (times > 0) {
                windowError = error to (times - 1)
                return WindowEncodeResult.Failed(error)
            }
        }
        if (!storage.exists(source)) return WindowEncodeResult.Failed(CodecError.SourceChanged)
        val model = library.model(source)
        val sourceBytes = storage.sizeBytes(source) ?: 0L
        val windowFraction = windows.sumOf { it.durationMs }.toDouble().coerceAtLeast(1.0)
        val sampleBytes = (sourceBytes * model.sizeFraction(setting) * windowFraction / 60_000.0)
            .toLong()
            .coerceAtLeast(1L)
        return WindowEncodeResult.Encoded(library.mintSample(source, setting), sampleBytes)
    }

    override suspend fun encodeFull(
        source: StorageRef,
        setting: EncodeSetting,
        destination: TempRef,
        onProgress: (Double) -> Unit,
    ): FullEncodeResult {
        fullEncodes += EncodeCall(source, setting)
        fullError?.let { (error, times) ->
            if (times > 0) {
                fullError = error to (times - 1)
                return FullEncodeResult.Failed(error)
            }
        }
        val model = library.model(source)
        val sourceBytes = storage.sizeBytes(source)
            ?: return FullEncodeResult.Failed(CodecError.SourceChanged)

        var fraction = 0.0
        while (fraction < 1.0) {
            fraction = (fraction + PROGRESS_STEP).coerceAtMost(1.0)
            if (fullEncodeDelayMs > 0) clock.sleep(fullEncodeDelayMs)

            mutateSourceAtFraction?.let { at ->
                if (fraction >= at) {
                    mutateSourceAtFraction = null
                    storage.touch(source)
                    return FullEncodeResult.Failed(CodecError.SourceChanged)
                }
            }
            if (reclaimsRemaining > 0 && fraction >= reclaimAtFraction) {
                reclaimsRemaining--
                return FullEncodeResult.Failed(CodecError.CodecReclaimed(fraction))
            }
            onProgress(fraction)
        }

        val encodedBytes = (sourceBytes * model.sizeFraction(setting)).toLong().coerceAtLeast(1L)
        if (encodedBytes > storage.freeSpaceOverride) {
            return FullEncodeResult.Failed(
                CodecError.OutOfSpace(encodedBytes, storage.freeSpaceOverride),
            )
        }
        storage.writeTemp(destination, encodedBytes)
        library.recordTempEncode(destination, source, setting)
        return FullEncodeResult.Encoded(
            bytes = encodedBytes,
            durationMs = DEFAULT_DURATION_MS,
            colorRange = model.colorRange,
        )
    }

    private companion object {
        const val PROGRESS_STEP = 0.25
        const val DEFAULT_DURATION_MS = 60_000L
    }
}
