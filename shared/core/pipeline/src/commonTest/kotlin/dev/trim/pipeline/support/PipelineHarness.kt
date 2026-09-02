package dev.trim.pipeline.support

import dev.trim.model.FolderId
import dev.trim.model.JobTrigger
import dev.trim.model.OriginalFate
import dev.trim.model.QualityTarget
import dev.trim.model.StorageRef
import dev.trim.model.Video
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.checks.HeadroomCheck
import dev.trim.pipeline.checks.NoiseCheck
import dev.trim.pipeline.encode.Encoder
import dev.trim.pipeline.encode.Verifier
import dev.trim.pipeline.predict.InMemoryPredictor
import dev.trim.pipeline.replace.Replacer
import dev.trim.pipeline.replace.UndoJournal
import dev.trim.pipeline.replace.UndoWriteResult
import dev.trim.pipeline.run.JobRequest
import dev.trim.pipeline.run.JobRunner
import dev.trim.pipeline.scan.ProcessedLedger
import dev.trim.pipeline.scan.Scanner
import dev.trim.pipeline.search.Prober
import dev.trim.pipeline.search.Searcher
import dev.trim.pipeline.triage.Triage
import dev.trim.ports.MediaHeader
import dev.trim.ports.fake.ContentModel
import dev.trim.ports.fake.FakeMediaInfo
import dev.trim.ports.fake.FakeScheduler
import dev.trim.ports.fake.FakeWorld
import dev.trim.model.UndoEntry
import dev.trim.model.VideoId

/**
 * A whole pipeline over fake ports, assembled the way the app assembles it. Tests that use
 * this are testing the composition, not the stages — the stages have their own suites.
 */
internal class PipelineHarness(
    val config: PipelineConfig = PipelineConfig(),
    processed: ProcessedLedger = object : ProcessedLedger {
        override suspend fun isProcessed(ref: StorageRef) = false
    },
) {
    val world: FakeWorld = FakeWorld()
    val journal: RecordingUndoJournal = RecordingUndoJournal()
    val predictor: InMemoryPredictor = InMemoryPredictor()

    val scanner: Scanner = Scanner(world.storage, world.mediaInfo, processed)
    val triage: Triage = Triage(config, predictor)

    val runner: JobRunner = JobRunner(
        storage = world.storage,
        clock = world.clock,
        thermal = world.thermal,
        triage = triage,
        noiseCheck = NoiseCheck(world.codec, config),
        headroomCheck = HeadroomCheck(world.scorer, config),
        prober = Prober(world.codec, world.scorer, config),
        searcher = Searcher(world.codec, world.scorer, config),
        encoder = Encoder(world.codec),
        verifier = Verifier(world.storage, world.scorer, config),
        replacer = Replacer(world.storage, world.clock, journal),
        predictor = predictor,
        config = config,
    )

    fun addVideo(
        name: String,
        sizeBytes: Long = 150L * 1024 * 1024,
        header: MediaHeader = FakeMediaInfo.header(),
        content: ContentModel = ContentModel.linear(),
        folder: FolderId = FolderId("dcim"),
    ): StorageRef = world.addVideo(
        ref = StorageRef("content://$folder/$name"),
        folder = folder,
        sizeBytes = sizeBytes,
        header = header,
        content = content,
    )

    suspend fun videoFor(ref: StorageRef): Video =
        scanner.scan().videos.single { it.ref == ref }

    fun request(
        trigger: JobTrigger = JobTrigger.USER_INITIATED,
        fate: OriginalFate = OriginalFate.KeptDays(30),
        target: QualityTarget = QualityTarget.BALANCED,
    ) = JobRequest(
        trigger = trigger,
        qualityTarget = target,
        originalFate = fate,
        conditions = FakeScheduler.PLUGGED_IN_AND_IDLE,
        workWhileUsingPhone = true,
    )
}

internal class RecordingUndoJournal : UndoJournal {
    val entries: LinkedHashMap<String, UndoEntry> = LinkedHashMap()
    var failNextWrite: String? = null

    override suspend fun record(entry: UndoEntry): UndoWriteResult {
        failNextWrite?.let {
            failNextWrite = null
            return UndoWriteResult.Failed(it)
        }
        entries[entry.videoId.value] = entry
        return UndoWriteResult.Written
    }

    override suspend fun forget(videoId: VideoId): Boolean = entries.remove(videoId.value) != null
}
