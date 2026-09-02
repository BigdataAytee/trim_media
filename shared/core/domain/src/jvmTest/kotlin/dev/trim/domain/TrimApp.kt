package dev.trim.domain

import dev.trim.data.CandidateDao
import dev.trim.data.HistoryDao
import dev.trim.data.JobDao
import dev.trim.data.JvmDriverFactory
import dev.trim.data.PredictorStore
import dev.trim.data.SettingsDao
import dev.trim.data.StartupReconciler
import dev.trim.data.UndoDao
import dev.trim.data.VideoDao
import dev.trim.data.trimDatabase
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.checks.HeadroomCheck
import dev.trim.pipeline.checks.NoiseCheck
import dev.trim.pipeline.encode.Encoder
import dev.trim.pipeline.encode.Verifier
import dev.trim.pipeline.replace.Replacer
import dev.trim.pipeline.replace.Restorer
import dev.trim.pipeline.run.JobRunner
import dev.trim.pipeline.scan.Scanner
import dev.trim.pipeline.search.Prober
import dev.trim.pipeline.search.Searcher
import dev.trim.pipeline.triage.Triage
import dev.trim.ports.fake.FakeWorld
import kotlinx.coroutines.Dispatchers

/**
 * The whole application, assembled over fake ports and a real (in-memory) database. This
 * is the composition root the Android app will mirror in Milestone 2 — the only difference
 * there is which implementations the seven ports resolve to.
 */
internal class TrimApp(
    val world: FakeWorld = FakeWorld(),
    val config: PipelineConfig = PipelineConfig(),
) {
    private val db = trimDatabase(JvmDriverFactory())
    private val context = Dispatchers.Default

    val videos: VideoDao = VideoDao(db, context)
    val candidates: CandidateDao = CandidateDao(db, context)
    val jobs: JobDao = JobDao(db)
    val history: HistoryDao = HistoryDao(db, context)
    val undo: UndoDao = UndoDao(db, context)
    val settings: SettingsDao = SettingsDao(db, context)
    val predictor: PredictorStore = PredictorStore(db)

    val scanner: Scanner = Scanner(world.storage, world.mediaInfo, history)
    val triage: Triage = Triage(config, predictor)

    private val replacer = Replacer(world.storage, world.clock, undo)
    private val restorer = Restorer(world.storage, world.clock, undo)

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
        replacer = replacer,
        predictor = predictor,
        config = config,
    )

    val scanAndTriage: ScanAndTriage =
        ScanAndTriage(scanner, triage, videos, candidates, world.clock)
    val observeCandidates: ObserveCandidates = ObserveCandidatesImpl(candidates)
    val queueForNight: QueueForNight =
        QueueForNightImpl(jobs, world.clock, world.scheduler, settings)
    val runQueue: RunQueue =
        RunQueue(runner, jobs, videos, history, undo, world.clock)
    val restoreOriginal: RestoreOriginal = RestoreOriginalImpl(restorer, undo, history)
    val observeHistory: ObserveHistory = ObserveHistoryImpl(history)
    val reconciler: StartupReconciler =
        StartupReconciler(world.storage, undo, jobs, history, world.clock)
}
