package dev.trim.data

import dev.trim.model.EstimateConfidence
import dev.trim.model.EstimateRange
import dev.trim.model.FolderId
import dev.trim.model.HistoryEntry
import dev.trim.model.Job
import dev.trim.model.JobId
import dev.trim.model.JobState
import dev.trim.model.JobTrigger
import dev.trim.model.OriginalFate
import dev.trim.model.QualityTarget
import dev.trim.model.SkipReason
import dev.trim.model.SkippedEntry
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.TrimSettings
import dev.trim.model.UndoEntry
import dev.trim.model.VideoCodec
import dev.trim.model.VideoId
import dev.trim.model.ColorRange
import dev.trim.model.Candidate
import dev.trim.model.Bracket
import dev.trim.model.EncodeSetting
import dev.trim.model.OutputCodec
import dev.trim.model.Video
import dev.trim.pipeline.replace.UndoWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataTest {

    private val driver = JvmDriverFactory()
    private val db = trimDatabase(driver)
    private val context = Dispatchers.Default
    private val videos = VideoDao(db, context)
    private val candidates = CandidateDao(db, context)
    private val jobs = JobDao(db)
    private val history = HistoryDao(db, context)
    private val undo = UndoDao(db, context)
    private val settings = SettingsDao(db, context)

    @AfterTest
    fun tearDown() {
        db.videosQueries.deleteAll()
    }

    private fun video(id: String = "v1", sizeBytes: Long = 150L * 1024 * 1024) = Video(
        id = VideoId("content://dcim/$id"),
        folderId = FolderId("dcim"),
        ref = StorageRef("content://dcim/$id"),
        displayName = "$id.mp4",
        sizeBytes = sizeBytes,
        durationMs = 60_000,
        width = 1920,
        height = 1080,
        frameRate = 30.0,
        codec = VideoCodec.H264,
        bitrateBps = 20_000_000,
        transfer = TransferFunction.SDR,
        colorRange = ColorRange.LIMITED,
        bitDepth = 8,
        videoTrackCount = 1,
        audioTrackCount = 1,
        otherTrackCount = 0,
        dateTakenEpochMs = 1_700_000_000_000,
        lastModifiedEpochMs = 1_700_000_000_000,
        fingerprint = SourceFingerprint(sizeBytes, 1_700_000_000_000, "hash-$id"),
    )

    @Test
    fun `a video survives the round trip unchanged`() {
        val subject = video()
        videos.upsert(subject, scannedAtEpochMs = 1)
        assertEquals(subject, videos.byRef(subject.ref))
    }

    @Test
    fun `the hub's shrinkable list is ordered by estimated saving`() = runTest {
        val small = video("small", sizeBytes = 50L * 1024 * 1024)
        val big = video("big", sizeBytes = 500L * 1024 * 1024)
        videos.upsertAll(listOf(small, big), scannedAtEpochMs = 1)

        candidates.recordCandidate(candidate(small, saving = 10L * 1024 * 1024), 1)
        candidates.recordCandidate(candidate(big, saving = 300L * 1024 * 1024), 1)

        val rows = candidates.observeShrinkable().first()
        assertEquals(listOf("big.mp4", "small.mp4"), rows.map { it.displayName })
    }

    @Test
    fun `a rejection cannot be stored without a reason`() = runTest {
        val subject = video()
        videos.upsert(subject, 1)
        candidates.recordRejection(subject.ref, SkipReason.TooNoisy(0.9), 1)

        val rows = candidates.observeNotShrinkable().first()
        val row = rows.single()
        assertIs<SkipReason.TooNoisy>(row.reason)
        assertEquals("too noisy to shrink", row.reason.displayText)

        // The schema itself refuses the shape: is_candidate = 0 with no reason_type.
        val threw = runCatching {
            db.candidatesQueries.upsert(
                dev.trim.data.db.Candidates(
                    video_ref = subject.ref.value,
                    is_candidate = 0,
                    estimate_low = null, estimate_high = null, estimate_confidence = null,
                    bracket_safest = null, bracket_aggressive = null, predicted_quality = null,
                    reason_type = null, reason_detail = null, judged_at_epoch_ms = 1,
                ),
            )
        }.isFailure
        assertTrue(threw, "the database accepted a rejection with no reason")
    }

    @Test
    fun `every reason survives a round trip as the same case`() {
        val reasons = listOf(
            SkipReason.AlreadyEfficient(VideoCodec.HEVC, 0.04),
            SkipReason.TooNoisy(0.9),
            SkipReason.Hdr(TransferFunction.PQ, 10),
            SkipReason.SecondaryTrack(1, 2, 0),
            SkipReason.TooSmall(1000, 8_388_608),
            SkipReason.NoHeadroom(dev.trim.model.vmaf(94.0), dev.trim.model.vmaf(97.0)),
            SkipReason.CannotReachTarget(dev.trim.model.xpsnr(30.0), dev.trim.model.xpsnr(41.0)),
            dev.trim.model.FailureReason.Cancelled,
            dev.trim.model.FailureReason.EncoderError("no hardware encoder"),
            dev.trim.model.FailureReason.OutOfSpace(100, 10),
            dev.trim.model.FailureReason.ReplaceRolledBack(3, "rename", "refused"),
            dev.trim.model.FailureReason.VerificationFailed(
                dev.trim.model.VerificationFailure.ScoreBelowTarget(
                    dev.trim.model.vmaf(93.0),
                    dev.trim.model.vmaf(95.0),
                ),
            ),
            dev.trim.model.FailureReason.VerificationFailed(
                dev.trim.model.VerificationFailure.NotSmaller(100, 120),
            ),
        )
        for (reason in reasons) {
            val decoded = Reasons.decode(Reasons.encodeType(reason), Reasons.encodeDetail(reason))
            assertNotNull(decoded, "$reason did not survive the round trip")
            assertEquals(
                reason::class.simpleName,
                decoded::class.simpleName,
                "$reason came back as a different case",
            )
            assertEquals(reason.displayText, decoded.displayText)
        }
    }

    @Test
    fun `a claimed job cannot be claimed twice`() {
        val job = Job(
            id = JobId("j1"),
            videoId = VideoId("content://dcim/v1"),
            trigger = JobTrigger.NIGHTLY,
            state = JobState.QUEUED,
            queuedAtEpochMs = 1,
            claimedAtEpochMs = null,
            claimToken = null,
        )
        jobs.enqueue(job)

        assertTrue(jobs.claim(job.id, "process-a", nowEpochMs = 10))
        assertFalse(jobs.claim(job.id, "process-b", nowEpochMs = 11))
        assertEquals("process-a", jobs.running().single().claimToken)
        assertTrue(jobs.queued().isEmpty())
    }

    @Test
    fun `one video cannot be queued twice while a job for it is pending`() {
        val first = Job(JobId("j1"), VideoId("v"), JobTrigger.NIGHTLY, JobState.QUEUED, 1, null, null)
        jobs.enqueue(first)
        // INSERT OR IGNORE plus the partial unique index: the second enqueue is a no-op,
        // which is what stops a file being encoded twice.
        jobs.enqueue(first.copy(id = JobId("j2")))
        assertEquals(1, jobs.queued().size)
    }

    @Test
    fun `a claim held by a dead process is released at startup`() {
        val job = Job(JobId("j1"), VideoId("v"), JobTrigger.NIGHTLY, JobState.QUEUED, 1, null, null)
        jobs.enqueue(job)
        jobs.claim(job.id, "old-process", nowEpochMs = 10)

        val released = jobs.releaseStaleClaims("new-process", processStartedAtEpochMs = 100)

        assertEquals(1, released)
        assertEquals(JobState.QUEUED, jobs.queued().single().state)
    }

    @Test
    fun `the processed list is a hard gate`() = runTest {
        val subject = video()
        assertFalse(history.isProcessed(subject.ref))
        history.recordCompleted(
            HistoryEntry(
                videoId = subject.id,
                displayName = subject.displayName,
                completedAtEpochMs = 100,
                originalBytes = subject.sizeBytes,
                compressedBytes = subject.sizeBytes / 2,
                originalFate = OriginalFate.KeptDays(30),
                restorableUntilEpochMs = 200,
            ),
        )
        assertTrue(history.isProcessed(subject.ref))
        assertEquals(subject.sizeBytes / 2, history.lifetimeSavedBytes())
    }

    @Test
    fun `history refuses a row that records no saving`() {
        val threw = runCatching {
            db.historyQueries.recordCompleted(
                dev.trim.data.db.History("v", "v.mp4", 1, 100, 100, "deleted", null, null),
            )
        }.isFailure
        assertTrue(threw, "the database accepted a history row with no saving")
    }

    @Test
    fun `the skipped list holds failures as well as skips`() {
        history.recordSkipped(
            SkippedEntry(VideoId("a"), "a.mp4", 1, SkipReason.TooNoisy(0.9)),
        )
        history.recordSkipped(
            SkippedEntry(VideoId("b"), "b.mp4", 2, dev.trim.model.FailureReason.Cancelled),
        )
        val rows = history.skipped()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.reason.displayText.isNotBlank() })
    }

    @Test
    fun `an undo entry round trips and can be forgotten`() = runTest {
        val entry = UndoEntry(
            videoId = VideoId("content://dcim/v1"),
            originalRef = StorageRef("content://dcim/v1"),
            binRef = StorageRef("bin://v1"),
            compressedRef = StorageRef("content://dcim/v1"),
            compressedFingerprint = SourceFingerprint(50, 100, "hash"),
            createdAtEpochMs = 100,
            expiresAtEpochMs = 200,
        )
        assertEquals(UndoWriteResult.Written, undo.record(entry))
        assertEquals(entry, undo.byId(entry.videoId))
        assertEquals(listOf(entry), undo.expired(nowEpochMs = 250))
        assertTrue(undo.expired(nowEpochMs = 150).isEmpty())
        assertTrue(undo.forget(entry.videoId))
        assertFalse(undo.forget(entry.videoId))
    }

    @Test
    fun `settings round trip and default when absent`() = runTest {
        assertEquals(TrimSettings.DEFAULT, settings.load())
        val changed = TrimSettings.DEFAULT.copy(
            qualityTarget = QualityTarget.SMALLEST,
            nightlyByteCap = 5L * 1024 * 1024 * 1024,
            defaultOriginalFate = OriginalFate.Offloaded("SD card"),
        )
        settings.save(changed)
        assertEquals(changed, settings.load())
        assertEquals(changed, settings.observe().first())
    }

    @Test
    fun `folder modes round trip`() {
        settings.setFolderMode(
            FolderId("dcim"),
            OriginalFate.KeptDays(7),
            includeInNightly = true,
            deleteConfirmed = false,
        )
        val mode = settings.folderMode(FolderId("dcim"))
        assertEquals(OriginalFate.KeptDays(7), mode?.fate)
        assertEquals(true, mode?.includeInNightly)
    }

    private fun candidate(subject: Video, saving: Long) = Candidate(
        video = subject,
        estimate = EstimateRange(
            lowBytes = subject.sizeBytes - saving - 1_000,
            highBytes = subject.sizeBytes - saving,
            confidence = EstimateConfidence.SEED,
        ),
        bracket = Bracket(
            EncodeSetting(20, OutputCodec.HEVC),
            EncodeSetting(32, OutputCodec.HEVC),
        ),
        predictedSetting = null,
    )
}

class PredictorStoreTest {
    private val db = trimDatabase(JvmDriverFactory())
    private val store = PredictorStore(db)

    private val key = dev.trim.pipeline.predict.PredictionKey(
        deviceClass = "pixel-8",
        cameraTag = null,
        codec = VideoCodec.H264,
        widthBucket = 1920,
        fpsBucket = 30,
        bitrateBucket = 32,
    )

    @Test
    fun `an unknown bucket has no prediction rather than a made-up one`() {
        kotlin.test.assertNull(store.suggest(key))
    }

    @Test
    fun `observations average and earn predicted confidence at the second one`() {
        store.observe(key, EncodeSetting(24, OutputCodec.HEVC), sizeFraction = 0.50)
        val first = store.suggest(key)!!
        assertEquals(24, first.setting.quality)
        assertEquals(EstimateConfidence.SEED, first.confidence)

        store.observe(key, EncodeSetting(26, OutputCodec.HEVC), sizeFraction = 0.40)
        val second = store.suggest(key)!!
        assertEquals(25, second.setting.quality)
        assertEquals(0.45, second.sizeFraction, absoluteTolerance = 1e-9)
        assertEquals(EstimateConfidence.PREDICTED, second.confidence)
    }

    @Test
    fun `the first real observation replaces a seed rather than averaging with it`() {
        store.installSeeds(
            mapOf(
                key to dev.trim.pipeline.predict.Prediction(
                    EncodeSetting(20, OutputCodec.HEVC),
                    sizeFraction = 0.80,
                    observations = 5,
                ),
            ),
        )
        assertEquals(20, store.suggest(key)!!.setting.quality)

        store.observe(key, EncodeSetting(28, OutputCodec.HEVC), sizeFraction = 0.35)
        val after = store.suggest(key)!!
        assertEquals(28, after.setting.quality, "a guess must not dilute a measurement")
        assertEquals(1, after.observations)
    }

    @Test
    fun `a seed never overwrites an observation`() {
        store.observe(key, EncodeSetting(28, OutputCodec.HEVC), sizeFraction = 0.35)
        store.installSeeds(
            mapOf(
                key to dev.trim.pipeline.predict.Prediction(
                    EncodeSetting(20, OutputCodec.HEVC), 0.80, 5,
                ),
            ),
        )
        assertEquals(28, store.suggest(key)!!.setting.quality)
    }
}
