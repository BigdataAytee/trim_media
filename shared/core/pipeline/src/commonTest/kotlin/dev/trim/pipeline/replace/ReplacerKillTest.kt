package dev.trim.pipeline.replace

import dev.trim.model.OriginalFate
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.pipeline.support.video
import dev.trim.ports.OriginalDestination
import dev.trim.ports.fake.FakeStorage
import dev.trim.ports.fake.FakeWorld
import dev.trim.ports.fake.StorageOp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Kill the six-step commit at each of its six steps and assert that the world is exactly
 * as it was — not approximately, not "the original is still around somewhere", but the
 * same [FakeStorage.Snapshot] and the same undo journal.
 *
 * The second assertion in each case is the one that makes the sequence's *ordering* worth
 * anything: at the moment of failure, the original file existed. That is the invariant
 * app-architecture §6 claims falls out of the ordering, and it is checked at every step
 * rather than inferred.
 */
class ReplacerKillTest {

    private val original = StorageRef("content://dcim/clip.mp4")

    private class Fixture {
        val world = FakeWorld()
        val journal = InMemoryUndoJournal()
        val video = video(id = "clip").copy(
            ref = StorageRef("content://dcim/clip.mp4"),
            sizeBytes = 150L * 1024 * 1024,
        )
        var temp: TempRef = TempRef("unset")

        suspend fun setUp(): Fixture {
            world.storage.addFile(
                folder = video.folderId,
                ref = video.ref,
                bytes = video.sizeBytes,
                lastModifiedEpochMs = video.lastModifiedEpochMs,
                dateTakenEpochMs = video.dateTakenEpochMs,
                hash = video.fingerprint.headTailHash,
            )
            temp = world.storage.createTemp("clip")
            world.storage.writeTemp(temp, 60L * 1024 * 1024)
            return this
        }

        fun replacer() = Replacer(world.storage, world.clock, journal)

        fun request(fate: OriginalFate = OriginalFate.KeptDays(30)) = ReplaceRequest(
            video = video,
            temp = temp,
            fate = fate,
            destination = OriginalDestination.UndoBin,
        )
    }

    private suspend fun fixture(): Fixture = Fixture().setUp()

    @Test
    fun `the happy path commits and records an undo entry`() = runTest {
        val f = fixture()
        val result = f.replacer().commit(f.request())

        assertIs<ReplaceResult.Committed>(result)
        assertEquals(60L * 1024 * 1024, result.compressedBytes)
        // The compressed file now occupies the original's exact path and name.
        assertTrue(f.world.storage.exists(original))
        assertEquals(60L * 1024 * 1024, f.world.storage.sizeBytes(original))
        // The original is in the bin, and restorable.
        assertTrue(f.world.storage.exists(StorageRef("bin://clip.mp4")))
        assertEquals(1, f.journal.entries.size)
        // Timestamps were restored and the gallery was told.
        assertEquals(
            f.video.lastModifiedEpochMs,
            f.world.storage.files.getValue(original.value).lastModifiedEpochMs,
        )
        assertEquals(listOf(original.value), f.world.storage.mediaScans)
        // Order is the order the document states.
        assertEquals(
            listOf("copyMetadata", "moveOriginal", "promoteTemp", "restoreTimestamps", "mediaScan"),
            f.world.storage.journal.filter { it.first().isLowerCase() }
                .map { it.substringBefore(' ') }
                .filter { it != "createTemp" },
        )
    }

    // ---- the six kill tests ----

    @Test
    fun `killed at step 1 - copy metadata`() = runTest {
        assertRollsBackCleanly(StorageOp.COPY_METADATA, CommitStep.CopyMetadata)
    }

    @Test
    fun `killed at step 2 - move original`() = runTest {
        assertRollsBackCleanly(StorageOp.MOVE_ORIGINAL, CommitStep.MoveOriginal)
    }

    @Test
    fun `killed at step 3 - promote temp`() = runTest {
        assertRollsBackCleanly(StorageOp.PROMOTE_TEMP, CommitStep.PromoteTemp)
    }

    @Test
    fun `killed at step 4 - restore timestamps`() = runTest {
        assertRollsBackCleanly(StorageOp.RESTORE_TIMESTAMPS, CommitStep.RestoreTimestamps)
    }

    @Test
    fun `killed at step 5 - trigger media scan`() = runTest {
        // The rollback of step 2 re-scans the restored original, so the injected failure
        // must apply only to the first call.
        assertRollsBackCleanly(StorageOp.TRIGGER_MEDIA_SCAN, CommitStep.TriggerMediaScan)
    }

    @Test
    fun `killed at step 6 - write undo entry`() = runTest {
        val f = fixture()
        val before = f.world.storage.snapshot()
        f.journal.failNextWrite = "database is locked"

        val result = f.replacer().commit(f.request())

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(CommitStep.WriteUndoEntry, result.failedStep)
        assertEquals(emptyList(), result.rollbackProblems)
        assertEquals(before.files, f.world.storage.snapshot().files)
        assertTrue(f.journal.entries.isEmpty())
    }

    private suspend fun assertRollsBackCleanly(op: StorageOp, expected: CommitStep) {
        val f = fixture()
        val before = f.world.storage.snapshot()
        f.world.storage.failOn(op, detail = "injected at ${expected.number}")

        // The original must exist at every moment of the sequence, not merely at the end.
        val sightings = mutableListOf<Boolean>()
        f.world.storage.beforeOp = {
            sightings += f.world.storage.files.keys.any { key ->
                key == original.value || key.startsWith("bin://")
            }
        }

        val result = f.replacer().commit(f.request())

        assertIs<ReplaceResult.RolledBack>(result)
        assertEquals(expected, result.failedStep)
        assertEquals(
            emptyList(),
            result.rollbackProblems,
            "the rollback of step ${expected.number} was not clean",
        )
        // User storage, byte for byte. Scratch space is deliberately excluded: step 1
        // writes metadata into the temp file and step 3 consumes it, and neither is a
        // change to anything the user owns. The runner deletes the temp in a `finally`.
        assertEquals(
            before.files,
            f.world.storage.snapshot().files,
            "killing step ${expected.number} (${expected.description}) did not restore the " +
                "exact prior state of user storage",
        )
        assertTrue(
            sightings.isNotEmpty() && sightings.all { it },
            "the original went missing part-way through step ${expected.number}",
        )
        assertTrue(f.journal.entries.isEmpty())
    }

    // ---- the invariants the ordering exists to produce ----

    @Test
    fun `nothing user-visible changes before the atomic step`() = runTest {
        val f = fixture()
        val before = f.world.storage.snapshot()
        val statesBeforePromote = mutableListOf<FakeStorage.Snapshot>()
        f.world.storage.beforeOp = { op ->
            if (op == StorageOp.PROMOTE_TEMP) statesBeforePromote += f.world.storage.snapshot()
        }

        f.replacer().commit(f.request())

        // At the moment step 3 begins, the only change is that the original has moved to
        // the bin — the file at the user's path is simply absent, never a partial write.
        val atPromote = statesBeforePromote.single()
        assertTrue(original.value !in atPromote.files)
        assertTrue("bin://clip.mp4" in atPromote.files)
        assertEquals(before.files.getValue(original.value), atPromote.files.getValue("bin://clip.mp4"))
    }

    @Test
    fun `a source that changed since the scan is refused before anything moves`() = runTest {
        val f = fixture()
        val before = f.world.storage.snapshot()
        f.world.storage.touch(f.video.ref)

        val result = f.replacer().commit(f.request())

        assertIs<ReplaceResult.Refused>(result)
        assertIs<dev.trim.model.FailureReason.SourceChanged>(result.reason)
        // The touch itself changed the file, so compare everything except that.
        assertEquals(before.files.keys, f.world.storage.snapshot().files.keys)
        assertTrue(f.world.storage.journal.none { it.startsWith("moveOriginal") })
    }

    @Test
    fun `a rollback that cannot restore the original says so rather than pretending`() =
        runTest {
            val f = fixture()
            f.world.storage.failOn(StorageOp.PROMOTE_TEMP, detail = "no space")
            f.world.storage.failOn(StorageOp.MOVE_BACK, detail = "volume unmounted")

            val result = f.replacer().commit(f.request())

            assertIs<ReplaceResult.RolledBack>(result)
            assertEquals(CommitStep.PromoteTemp, result.failedStep)
            assertEquals(1, result.rollbackProblems.size)
            assertTrue(result.rollbackProblems.single().contains("could not restore the original"))
            // Even then the original exists — in the bin, which is where restore looks.
            assertTrue(f.world.storage.exists(StorageRef("bin://clip.mp4")))
        }

    @Test
    fun `an offloaded original expires never, a kept one expires on schedule`() = runTest {
        val f = fixture()
        val result = f.replacer().commit(f.request(OriginalFate.KeptDays(30)))
        assertIs<ReplaceResult.Committed>(result)
        assertEquals(
            f.world.clock.nowEpochMs() + 30L * 24 * 60 * 60 * 1000,
            result.undoEntry.expiresAtEpochMs,
        )

        val g = fixture()
        val offloaded = g.replacer().commit(g.request(OriginalFate.Offloaded("SD card")))
        assertIs<ReplaceResult.Committed>(offloaded)
        assertEquals(null, offloaded.undoEntry.expiresAtEpochMs)
    }
}
