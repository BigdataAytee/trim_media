package dev.trim.data

import dev.trim.model.FolderId
import dev.trim.model.Job
import dev.trim.model.JobId
import dev.trim.model.JobState
import dev.trim.model.JobTrigger
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.UndoEntry
import dev.trim.model.VideoId
import dev.trim.ports.fake.FakeClock
import dev.trim.ports.fake.FakeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reconciler exists because the database and the filesystem are two things that can
 * disagree, and app-architecture §4.1 says the disagreement is repaired on every start
 * rather than discovered by a user whose restore button does nothing.
 */
class StartupReconcilerTest {

    private val db = trimDatabase(JvmDriverFactory())
    private val clock = FakeClock(startEpochMs = 1_000_000)
    private val storage = FakeStorage(clock)
    private val undo = UndoDao(db, Dispatchers.Default)
    private val jobs = JobDao(db)
    private val history = HistoryDao(db, Dispatchers.Default)
    private val reconciler = StartupReconciler(storage, undo, jobs, history, clock)

    private fun entry(
        id: String,
        expiresAtEpochMs: Long? = 2_000_000,
    ) = UndoEntry(
        videoId = VideoId(id),
        originalRef = StorageRef("content://dcim/$id"),
        binRef = StorageRef("bin://$id"),
        compressedRef = StorageRef("content://dcim/$id"),
        compressedFingerprint = SourceFingerprint(50, 100, "hash-$id"),
        createdAtEpochMs = 900_000,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    @Test
    fun `a clean start reports that it is clean rather than saying nothing`() = runTest {
        val report = reconciler.reconcile("process-1", 1_000_000, binContents = emptyList())
        assertTrue(report.isClean)
        assertTrue(report.describe().contains("nothing to repair"))
    }

    @Test
    fun `an undo entry whose file is gone is withdrawn`() = runTest {
        undo.record(entry("a"))

        val report = reconciler.reconcile("process-1", 1_000_000, binContents = emptyList())

        assertEquals(1, report.repairs.size)
        assertIs<Repair.UndoEntryWithoutFile>(report.repairs.single())
        assertTrue(undo.all().isEmpty(), "the entry promised a restore that cannot happen")
    }

    @Test
    fun `a file in the bin with no entry is kept, never deleted`() = runTest {
        val orphan = StorageRef("bin://orphan.mp4")
        storage.addFile(FolderId("bin"), orphan, bytes = 123_456)

        val report = reconciler.reconcile("process-1", 1_000_000, binContents = listOf(orphan))

        val repair = report.repairs.single()
        assertIs<Repair.OrphanedBinFile>(repair)
        assertEquals(123_456, repair.sizeBytes)
        assertTrue(
            storage.exists(orphan),
            "an orphaned bin file is the user's video; tidiness is not a reason to delete it",
        )
    }

    @Test
    fun `a job claimed by a dead process goes back on the queue`() = runTest {
        val job = Job(JobId("j1"), VideoId("v"), JobTrigger.NIGHTLY, JobState.QUEUED, 1, null, null)
        jobs.enqueue(job)
        jobs.claim(job.id, "process-that-died", nowEpochMs = 10)

        val report = reconciler.reconcile("process-2", 1_000_000, binContents = emptyList())

        val repair = report.repairs.filterIsInstance<Repair.StaleClaimsReleased>().single()
        assertEquals(1, repair.count)
        assertEquals(1, jobs.queued().size)
    }

    @Test
    fun `an expired retention window closes the entry`() = runTest {
        val expired = entry("old", expiresAtEpochMs = 950_000)
        storage.addFile(FolderId("bin"), expired.binRef, bytes = 10)
        undo.record(expired)

        val report = reconciler.reconcile("process-1", 1_000_000, binContents = listOf(expired.binRef))

        assertIs<Repair.RetentionWindowExpired>(report.repairs.single())
        assertTrue(undo.all().isEmpty())
    }

    @Test
    fun `an entry within its window is left alone`() = runTest {
        val live = entry("fresh", expiresAtEpochMs = 5_000_000)
        storage.addFile(FolderId("bin"), live.binRef, bytes = 10)
        undo.record(live)

        val report = reconciler.reconcile("process-1", 1_000_000, binContents = listOf(live.binRef))

        assertTrue(report.isClean)
        assertEquals(listOf(live), undo.all())
    }

    @Test
    fun `every repair is described, never silent`() = runTest {
        undo.record(entry("missing"))
        val orphan = StorageRef("bin://orphan.mp4")
        storage.addFile(FolderId("bin"), orphan, bytes = 1)
        val job = Job(JobId("j1"), VideoId("v"), JobTrigger.NIGHTLY, JobState.QUEUED, 1, null, null)
        jobs.enqueue(job)
        jobs.claim(job.id, "dead", nowEpochMs = 10)

        val report = reconciler.reconcile("alive", 1_000_000, binContents = listOf(orphan))

        assertEquals(3, report.repairs.size)
        val text = report.describe()
        report.repairs.forEach { repair ->
            assertTrue(repair.describe() in text, "$repair was repaired without being recorded")
        }
    }
}
