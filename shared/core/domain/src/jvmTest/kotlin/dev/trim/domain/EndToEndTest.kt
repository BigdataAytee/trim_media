package dev.trim.domain

import dev.trim.model.FolderId
import dev.trim.model.HistoryEntry
import dev.trim.model.JobOutcome
import dev.trim.model.OriginalFate
import dev.trim.model.RestoreResult
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.TrimSettings
import dev.trim.model.VideoCodec
import dev.trim.model.VideoId
import dev.trim.ports.MediaHeader
import dev.trim.ports.fake.ContentModel
import dev.trim.ports.fake.FakeMediaInfo
import dev.trim.ports.fake.FakeScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scan → queue → run, over a seeded library of fifteen videos of the kinds a real gallery
 * contains, against every port faked and a real (in-memory) database.
 *
 * The assertion is the table below, written out in full and compared exactly: not "most
 * files were handled sensibly" but *this file gets this outcome, and no other*. If a
 * threshold in PipelineConfig moves, this table is what notices.
 */
class EndToEndTest {

    /**
     * | # | file | why it is here | expected |
     * |---|---|---|---|
     * | 1 | beach.mp4 | ordinary bloated H.264 from a phone camera | compressed |
     * | 2 | birthday.mp4 | another one, so a batch is a batch | compressed |
     * | 3 | dog.mp4 | and a third | compressed |
     * | 4 | already-trim.mp4 | efficient HEVC, the shape Trim's own output has | already efficient |
     * | 5 | wedding.mp4 | bloated HEVC — the codec is modern, the bitrate is not | compressed |
     * | 6 | concert.mp4 | shot at night; grain dominates | too noisy |
     * | 7 | sunset-hdr.mp4 | PQ transfer | HDR |
     * | 8 | fireworks-10bit.mp4 | 10-bit, SDR transfer — still HDR-ish, still hands off | HDR |
     * | 9 | clip.mp4 | 4 MB; the work costs more than the saving | too small |
     * | 10 | interview.mp4 | two audio tracks the encoder would drop | extra tracks |
     * | 11 | soft-focus.mp4 | cannot score above the target against itself | no headroom |
     * | 12 | tricky.mp4 | scores badly even at the safest setting | can't be shrunk |
     * | 13 | drone-4k60.mp4 | 20 Mbps, which is thrifty at 4K60 and bloated at 1080p30 | already efficient |
     * | 14 | holiday.mp4 | Trim compressed this last month | never scanned again |
     * | 15 | timelapse.mp4 | bloated VP9 | compressed |
     */
    private data class Expectation(val name: String, val outcome: String)

    private val expected = listOf(
        Expectation("beach.mp4", "compressed"),
        Expectation("birthday.mp4", "compressed"),
        Expectation("dog.mp4", "compressed"),
        Expectation("already-trim.mp4", "already efficiently encoded"),
        Expectation("wedding.mp4", "compressed"),
        Expectation("concert.mp4", "too noisy to shrink"),
        Expectation("sunset-hdr.mp4", "HDR video is left untouched"),
        Expectation("fireworks-10bit.mp4", "HDR video is left untouched"),
        Expectation("clip.mp4", "too small to be worth shrinking"),
        Expectation("interview.mp4", "has extra tracks that would be lost"),
        Expectation("soft-focus.mp4", "not enough quality headroom to shrink safely"),
        Expectation("tricky.mp4", "can't be shrunk without visible loss"),
        Expectation("drone-4k60.mp4", "already efficiently encoded"),
        Expectation("timelapse.mp4", "compressed"),
    )

    private val dcim = FolderId("dcim")

    private fun bitrateFor(bpp: Double, width: Int = 1920, height: Int = 1080, fps: Double = 30.0) =
        (bpp * width * height * fps).toLong()

    private fun seed(app: TrimApp) {
        fun add(
            name: String,
            sizeMb: Long = 150,
            header: MediaHeader = FakeMediaInfo.header(),
            content: ContentModel = ContentModel.linear(),
        ) = app.world.addVideo(
            ref = StorageRef("content://dcim/$name"),
            folder = dcim,
            sizeBytes = sizeMb * 1024 * 1024,
            header = header,
            content = content,
        )

        add("beach.mp4", sizeMb = 380)
        add("birthday.mp4", sizeMb = 210)
        add("dog.mp4", sizeMb = 95)
        add(
            "already-trim.mp4",
            sizeMb = 60,
            header = FakeMediaInfo.header(
                codec = VideoCodec.HEVC,
                bitrateBps = bitrateFor(0.040),
            ),
        )
        add(
            "wedding.mp4",
            sizeMb = 500,
            header = FakeMediaInfo.header(
                codec = VideoCodec.HEVC,
                bitrateBps = bitrateFor(0.120),
            ),
        )
        add("concert.mp4", sizeMb = 240, content = ContentModel.noisy())
        add(
            "sunset-hdr.mp4",
            sizeMb = 180,
            header = FakeMediaInfo.header(transfer = TransferFunction.PQ, bitDepth = 10),
        )
        add(
            "fireworks-10bit.mp4",
            sizeMb = 190,
            header = FakeMediaInfo.header(bitDepth = 10),
        )
        add("clip.mp4", sizeMb = 4)
        add(
            "interview.mp4",
            sizeMb = 300,
            header = FakeMediaInfo.header(audioTrackCount = 2),
        )
        add("soft-focus.mp4", sizeMb = 160, content = ContentModel.ceilingBound())
        add("tricky.mp4", sizeMb = 275, content = ContentModel.stubborn())
        add(
            "drone-4k60.mp4",
            sizeMb = 900,
            header = FakeMediaInfo.header(
                width = 3840,
                height = 2160,
                frameRate = 60.0,
                bitrateBps = 20_000_000,
            ),
        )
        add("holiday.mp4", sizeMb = 120)
        add(
            "timelapse.mp4",
            sizeMb = 260,
            header = FakeMediaInfo.header(
                codec = VideoCodec.VP9,
                bitrateBps = bitrateFor(0.100),
            ),
        )
    }

    @Test
    fun `a whole library scans, queues, runs, and lands exactly where the table says`() =
        runTest {
            val app = TrimApp()
            seed(app)
            app.settings.save(TrimSettings.DEFAULT)

            // holiday.mp4 was compressed last month. The processed list is a hard gate, so
            // it must not even appear in the scan — generational loss is prevented
            // structurally, not by warning copy (app-architecture §12).
            app.history.recordCompleted(
                HistoryEntry(
                    videoId = VideoId("content://dcim/holiday.mp4"),
                    displayName = "holiday.mp4",
                    completedAtEpochMs = 1,
                    originalBytes = 120L * 1024 * 1024,
                    compressedBytes = 60L * 1024 * 1024,
                    originalFate = OriginalFate.Deleted,
                    restorableUntilEpochMs = null,
                ),
            )

            // ---- scan ----
            val report = app.scanAndTriage()
            assertEquals(14, report.videos.size, "the already-processed file must not be rescanned")
            assertTrue(report.videos.none { it.displayName == "holiday.mp4" })
            assertEquals(1, report.alreadyProcessed.size)

            // The hub renders the database, not the pipeline.
            val snapshot = app.observeCandidates().first()
            // Eight candidates, ordered by estimated saving. concert, soft-focus and
            // tricky are here because triage cannot tell from a header that they are noisy,
            // ceiling-bound or stubborn — only an encode can, and the hub renders what is
            // known at scan time.
            assertEquals(
                listOf(
                    "beach.mp4", "wedding.mp4", "tricky.mp4", "concert.mp4", "birthday.mp4",
                    "soft-focus.mp4", "timelapse.mp4", "dog.mp4",
                ),
                snapshot.shrinkable.map { it.displayName },
                "the hub sorts by estimated saving, descending",
            )
            // The top three rows carry roughly half the promised total (frontend §5).
            val topThree = snapshot.shrinkable.take(3)
                .sumOf { it.sizeBytes - it.estimate.highBytes }
            assertTrue(
                topThree >= snapshot.totalFreeable.lowBytes / 2,
                "the top three rows should carry about half the promised total",
            )
            assertEquals(
                setOf(
                    "already-trim.mp4", "sunset-hdr.mp4", "fireworks-10bit.mp4", "clip.mp4",
                    "interview.mp4", "drone-4k60.mp4",
                ),
                snapshot.notShrinkable.map { it.displayName }.toSet(),
                "triage's rejections are rows too, and they are the app's credibility",
            )
            assertTrue(snapshot.totalFreeable.lowBytes < snapshot.totalFreeable.highBytes)

            // ---- queue everything, including the files triage rejected: the runner is
            // the authority, and a queued rejection must come back with its reason ----
            app.queueForNight(report.videos.map { it.id })
            assertEquals(14, app.jobs.queued().size)
            assertNotNull(app.world.scheduler.nightlyConstraints)

            // ---- run ----
            val outcomes = app.runQueue(
                conditions = FakeScheduler.PLUGGED_IN_AND_IDLE,
                settings = app.settings.load(),
            )
            assertEquals(14, outcomes.size, "every queued job produced exactly one outcome")

            // ---- the table, compared exactly ----
            val actual = outcomes.associate { outcome ->
                val name = outcome.videoId.value.substringAfterLast('/')
                name to when (outcome) {
                    is JobOutcome.Compressed -> "compressed"
                    is JobOutcome.Skipped -> outcome.reason.displayText
                    is JobOutcome.Failed -> "FAILED: ${outcome.reason.displayText}"
                }
            }
            assertEquals(expected.associate { it.name to it.outcome }, actual)

            // ---- and the rows the run left behind ----
            val history = app.observeHistory().first()
            assertEquals(
                // Five newly compressed, plus holiday.mp4 from last month.
                setOf(
                    "beach.mp4", "birthday.mp4", "dog.mp4", "wedding.mp4", "timelapse.mp4",
                    "holiday.mp4",
                ),
                history.completed.map { it.displayName }.toSet(),
            )
            assertEquals(9, history.skipped.size)
            assertTrue(
                history.skipped.all { it.reason.displayText.isNotBlank() },
                "no row in the skipped list may be a bare 'skipped'",
            )
            assertTrue(history.lifetimeSavedBytes > 0)
            assertEquals(
                history.completed.sumOf { it.originalBytes - it.compressedBytes },
                history.lifetimeSavedBytes,
            )

            // Every compressed original is in the undo bin, restorable, and the compressed
            // file stands at the original's exact path.
            assertEquals(5, app.undo.all().size)
            for (entry in app.undo.all()) {
                assertTrue(app.world.storage.exists(entry.binRef), "original missing from the bin")
                assertTrue(app.world.storage.exists(entry.originalRef), "no file at the user's path")
                val compressed = app.world.storage.sizeBytes(entry.originalRef)!!
                val original = app.world.storage.sizeBytes(entry.binRef)!!
                assertTrue(compressed < original, "the 'compressed' file is not smaller")
            }

            // Nothing was left in scratch space, and every job reached a terminal state.
            assertTrue(app.world.storage.temps.isEmpty())
            assertTrue(app.jobs.queued().isEmpty() && app.jobs.running().isEmpty())

            // The predictor learned from the run, so the next scan's estimates sharpen.
            assertTrue(
                app.candidates.observeShrinkable().first().isEmpty() ||
                    app.predictor.suggest(
                        dev.trim.pipeline.predict.PredictionKey.of(
                            report.videos.single { it.displayName == "beach.mp4" },
                            "generic",
                        ),
                    ) != null,
            )

            // Diagnostics recorded the calibration fallback loudly, as §9 requires.
            assertTrue(
                app.runner.diagnostics.any { "generic curve" in it },
                "a device with no calibration must say so in diagnostics",
            )
        }

    @Test
    fun `a restore puts the original back and withdraws it from history`() = runTest {
        val app = TrimApp()
        seed(app)
        app.settings.save(TrimSettings.DEFAULT)
        val report = app.scanAndTriage()
        app.queueForNight(report.videos.filter { it.displayName == "beach.mp4" }.map { it.id })
        app.runQueue(FakeScheduler.PLUGGED_IN_AND_IDLE, app.settings.load())

        val entry = app.undo.all().single()
        val originalBytes = app.world.storage.sizeBytes(entry.binRef)!!

        val result = app.restoreOriginal(entry.videoId)

        assertIs<RestoreResult.Restored>(result)
        assertEquals(originalBytes, result.restoredBytes)
        assertEquals(originalBytes, app.world.storage.sizeBytes(entry.originalRef))
        assertTrue(app.undo.all().isEmpty())
        assertTrue(app.observeHistory().first().completed.isEmpty())
    }

    @Test
    fun `a restore is refused when the compressed file has been edited since`() = runTest {
        val app = TrimApp()
        seed(app)
        app.settings.save(TrimSettings.DEFAULT)
        val report = app.scanAndTriage()
        app.queueForNight(report.videos.filter { it.displayName == "beach.mp4" }.map { it.id })
        app.runQueue(FakeScheduler.PLUGGED_IN_AND_IDLE, app.settings.load())

        val entry = app.undo.all().single()
        // Another app edits the compressed file. Putting the original back now would
        // destroy that work rather than undo Trim's.
        app.world.storage.touch(entry.compressedRef)

        val result = app.restoreOriginal(entry.videoId)

        assertIs<RestoreResult.Refused>(result)
        assertEquals(
            dev.trim.model.RestoreRefusal.CompressedFileModified,
            result.reason,
        )
        assertTrue(app.world.storage.exists(entry.binRef), "the original stays in the bin")
    }

    @Test
    fun `a second scan offers nothing that was compressed in the first`() = runTest {
        val app = TrimApp()
        seed(app)
        app.settings.save(TrimSettings.DEFAULT)
        val first = app.scanAndTriage()
        app.queueForNight(first.videos.map { it.id })
        app.runQueue(FakeScheduler.PLUGGED_IN_AND_IDLE, app.settings.load())

        val second = app.scanAndTriage()

        val compressed = setOf(
            "beach.mp4", "birthday.mp4", "dog.mp4", "wedding.mp4", "timelapse.mp4",
        )
        assertTrue(
            second.videos.none { it.displayName in compressed },
            "a compressed file was offered again: generational loss is one run away",
        )
        assertEquals(compressed.size + 1, second.alreadyProcessed.size)
    }

    @Test
    fun `a skipped file is offered again, because a skip is not a processing`() = runTest {
        val app = TrimApp()
        seed(app)
        app.settings.save(TrimSettings.DEFAULT)
        val first = app.scanAndTriage()
        app.queueForNight(first.videos.map { it.id })
        app.runQueue(FakeScheduler.PLUGGED_IN_AND_IDLE, app.settings.load())

        val second = app.scanAndTriage()

        // The user may change the quality target, or a future release may handle HDR. A
        // skip records a decision, not a fact about the file forever.
        assertTrue(second.videos.any { it.displayName == "sunset-hdr.mp4" })
        assertTrue(second.videos.any { it.displayName == "concert.mp4" })
    }

    @Test
    fun `a run interrupted between files leaves a consistent database`() = runTest {
        val app = TrimApp()
        seed(app)
        app.settings.save(TrimSettings.DEFAULT)
        val report = app.scanAndTriage()
        app.queueForNight(report.videos.map { it.id })

        // Simulate the process dying with one job claimed: a second process starts, the
        // reconciler releases the claim, and the file is run rather than stranded.
        val job = app.jobs.queued().first()
        app.jobs.claim(job.id, "process-that-died", app.world.clock.nowEpochMs())
        assertEquals(1, app.jobs.running().size)

        val repairs = app.reconciler.reconcile(
            processToken = "process-2",
            processStartedAtEpochMs = app.world.clock.nowEpochMs() + 1,
            binContents = emptyList(),
        )
        assertTrue(repairs.repairs.any { it is dev.trim.data.Repair.StaleClaimsReleased })
        assertTrue(app.jobs.running().isEmpty())

        // Fifteen here, not fourteen: this test does not pre-record holiday.mp4 as
        // processed, so the whole seeded library is in play.
        val outcomes = app.runQueue(FakeScheduler.PLUGGED_IN_AND_IDLE, app.settings.load())
        assertEquals(15, outcomes.size, "the stranded job ran after the claim was released")
    }

    @Test
    fun `the reasons the hub shows and the reasons history shows are the same reasons`() =
        runTest {
            val app = TrimApp()
            seed(app)
            app.settings.save(TrimSettings.DEFAULT)
            val report = app.scanAndTriage()
            app.queueForNight(report.videos.map { it.id })
            app.runQueue(FakeScheduler.PLUGGED_IN_AND_IDLE, app.settings.load())

            val hubReasons = app.observeCandidates().first()
                .notShrinkable.associate { it.displayName to it.reason.displayText }
            val historyReasons = app.observeHistory().first()
                .skipped.associate { it.displayName to it.reason.displayText }

            for ((name, reason) in hubReasons) {
                assertEquals(
                    reason,
                    historyReasons[name],
                    "the hub and history disagree about why $name was skipped",
                )
            }
            // The three the runner rejected are in history but not in triage's list.
            assertEquals(
                setOf("concert.mp4", "soft-focus.mp4", "tricky.mp4"),
                historyReasons.keys - hubReasons.keys,
            )
        }
}
