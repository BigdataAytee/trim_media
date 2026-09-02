package dev.trim.ports.contract

import dev.trim.model.FolderId
import dev.trim.model.StorageRef
import dev.trim.ports.MoveResult
import dev.trim.ports.OriginalDestination
import dev.trim.ports.Storage
import dev.trim.ports.StorageListResult
import dev.trim.ports.StorageWriteResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The port the whole safety argument rests on.
 *
 * `core/pipeline`'s kill-tests prove the Replacer's *sequence* is correct given a storage
 * that behaves. This suite is the other half: it proves the storage underneath behaves the
 * way the sequence assumes. The last clause runs the six steps of app-architecture §6
 * against the port directly and checks the original is recoverable after each one — on a
 * real SAF implementation that is the difference between an invariant and a wish.
 */
public abstract class StorageContract {

    public interface Fixture : PortFixture {
        public val storage: Storage

        /** A folder the implementation has access to. */
        public val folder: FolderId

        /** Puts a file of [bytes] into [folder] and returns its ref. */
        public suspend fun seedVideo(name: String, bytes: Long): StorageRef

        /** A ref that is syntactically valid but names nothing. */
        public suspend fun missingRef(): StorageRef

        /**
         * Puts [bytes] of content into a temp file. The port itself has no way to write
         * scratch space — that is the Codec's job — so the fixture stands in for the
         * encoder here.
         */
        public suspend fun seedTemp(temp: dev.trim.model.TempRef, bytes: Long)
    }

    public abstract fun createFixture(): Fixture

    public fun cases(): List<ContractCase> = readClauses() + scratchClauses() + writeClauses() +
        listOf(sequenceClause())

    // ---- reading ----

    private fun readClauses(): List<ContractCase> = listOf(
        case("a granted folder lists the files in it") {
            withFixture { f ->
                val a = f.seedVideo("a.mp4", 1_000)
                val b = f.seedVideo("b.mp4", 2_000)
                val listing = f.storage.listVideos(f.folder)
                assertIs<StorageListResult.Listed>(listing)
                assertTrue(
                    listing.refs.containsAll(listOf(a, b)),
                    "listVideos returned ${listing.refs}, missing one of $a, $b",
                )
            }
        },
        case("a granted folder appears in the grant list") {
            withFixture { f ->
                f.seedVideo("a.mp4", 1_000)
                assertTrue(
                    f.folder in f.storage.grantedFolders(),
                    "the fixture's own folder was not reported as granted",
                )
            }
        },
        case("existence, size and fingerprint agree with one another") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 4_096)
                assertTrue(f.storage.exists(ref))
                assertEquals(4_096, f.storage.sizeBytes(ref))
                val fingerprint = f.storage.fingerprint(ref)
                assertTrue(fingerprint != null, "an existing file had no fingerprint")
                assertEquals(
                    4_096,
                    fingerprint.sizeBytes,
                    "the fingerprint and sizeBytes disagree about the same file",
                )
                assertTrue(
                    fingerprint.headTailHash.isNotBlank(),
                    "a blank hash makes the source-changed check a no-op",
                )
            }
        },
        case("a fingerprint is stable while the file is not touched") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 1_000)
                assertEquals(f.storage.fingerprint(ref), f.storage.fingerprint(ref))
            }
        },
        case("two different files do not share a fingerprint") {
            withFixture { f ->
                val a = f.seedVideo("a.mp4", 1_000)
                val b = f.seedVideo("b.mp4", 2_000)
                assertNotEquals(
                    f.storage.fingerprint(a),
                    f.storage.fingerprint(b),
                    "if two files fingerprint alike, the source-changed check cannot fire",
                )
            }
        },
        case("a missing file reports absent rather than throwing") {
            withFixture { f ->
                val missing = f.missingRef()
                assertTrue(!f.storage.exists(missing))
                assertNull(f.storage.sizeBytes(missing))
                assertNull(f.storage.fingerprint(missing))
            }
        },
        case("free space and volume are answerable for a real file") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 1_000)
                assertTrue(
                    f.storage.freeSpaceBytes(ref) > 0,
                    "free space of zero would fail every job with OutOfSpace",
                )
                assertTrue(
                    f.storage.volumeOf(ref).isNotBlank(),
                    "the Replacer prefers same-volume moves and needs a volume to compare",
                )
            }
        },
    )

    // ---- scratch space ----

    private fun scratchClauses(): List<ContractCase> = listOf(
        case("temp files are unique and disposable") {
            withFixture { f ->
                val first = f.storage.createTemp("clip")
                val second = f.storage.createTemp("clip")
                assertNotEquals(
                    first,
                    second,
                    "two temps with the same hint collided; one job would overwrite another",
                )
                f.storage.deleteTemp(first)
                assertNull(f.storage.tempSizeBytes(first))
                f.storage.deleteTemp(second)
            }
        },
        case("deleting a temp twice is not an error") {
            withFixture { f ->
                // The runner deletes the temp in a `finally`, which can run after a path
                // that already deleted it.
                val temp = f.storage.createTemp("clip")
                f.storage.deleteTemp(temp)
                f.storage.deleteTemp(temp)
            }
        },
    )

    // ---- writing ----

    private fun writeClauses(): List<ContractCase> = listOf(
        case("moving an original leaves it readable at its new ref") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 8_192)
                val moved = f.storage.moveOriginal(ref, OriginalDestination.UndoBin)
                assertIs<MoveResult.Moved>(moved)
                assertTrue(!f.storage.exists(ref), "the original is still at its old path")
                assertTrue(f.storage.exists(moved.to), "the original is not at its new path")
                assertEquals(
                    8_192,
                    f.storage.sizeBytes(moved.to),
                    "the move changed the file's size",
                )
            }
        },
        case("moving back is the exact inverse of moving out") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 8_192)
                val before = f.storage.fingerprint(ref)
                val moved = f.storage.moveOriginal(ref, OriginalDestination.UndoBin)
                assertIs<MoveResult.Moved>(moved)
                assertIs<MoveResult.Moved>(f.storage.moveBack(moved.to, ref))
                assertEquals(
                    before,
                    f.storage.fingerprint(ref),
                    "a rollback restored a file that is not the file it moved",
                )
            }
        },
        case("promoting a temp puts its bytes at the target and consumes the temp") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 8_192)
                val temp = f.storage.createTemp("a")
                f.seedTemp(temp, 4_096)
                assertIs<MoveResult.Moved>(
                    f.storage.moveOriginal(ref, OriginalDestination.UndoBin),
                )
                assertEquals(StorageWriteResult.Written, f.storage.promoteTemp(temp, ref))
                assertEquals(4_096, f.storage.sizeBytes(ref))
                assertNull(
                    f.storage.tempSizeBytes(temp),
                    "the temp survived promotion; the next `finally` would delete the " +
                        "user's new file",
                )
            }
        },
        case("restoring timestamps changes the recorded modification time") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 1_000)
                val stamp = 1_600_000_000_000
                assertEquals(
                    StorageWriteResult.Written,
                    f.storage.restoreTimestamps(ref, stamp, stamp),
                )
                assertEquals(
                    stamp,
                    f.storage.fingerprint(ref)?.lastModifiedEpochMs,
                    "the timestamp did not survive; galleries would re-sort the user's roll",
                )
            }
        },
        case("a media scan is accepted for a file that exists") {
            withFixture { f ->
                val ref = f.seedVideo("a.mp4", 1_000)
                assertEquals(StorageWriteResult.Written, f.storage.triggerMediaScan(ref))
            }
        },
        case("every write to a missing file fails rather than throwing") {
            withFixture { f ->
                // Every one of these can be reached by a rollback racing another app.
                val missing = f.missingRef()
                assertIs<MoveResult.Failed>(
                    f.storage.moveOriginal(missing, OriginalDestination.UndoBin),
                )
                assertIs<MoveResult.Failed>(f.storage.moveBack(missing, missing))
                assertIs<StorageWriteResult.Failed>(f.storage.deleteWritten(missing))
            }
        },
    )

    // ---- the sequence itself ----

    private fun sequenceClause(): ContractCase =
        case("the six-step commit keeps the original recoverable at every step") {
            withFixture { f ->
                val ref = f.seedVideo("clip.mp4", 10_000)
                val originalFingerprint = f.storage.fingerprint(ref)
                val temp = f.storage.createTemp("clip")
                f.seedTemp(temp, 5_000)

                suspend fun originalIsRecoverable(step: String, movedTo: StorageRef?) {
                    val here = if (movedTo == null) ref else movedTo
                    assertTrue(
                        f.storage.exists(here),
                        "after step $step the original was not at $here — " +
                            "app-architecture §6 says it exists at every intermediate step",
                    )
                }

                // 1 — metadata into the new file
                assertEquals(StorageWriteResult.Written, f.storage.copyMetadata(ref, temp))
                originalIsRecoverable("1", movedTo = null)

                // 2 — the original leaves its place
                val moved = f.storage.moveOriginal(ref, OriginalDestination.UndoBin)
                assertIs<MoveResult.Moved>(moved)
                originalIsRecoverable("2", moved.to)

                // 3 — the atomic point
                assertEquals(StorageWriteResult.Written, f.storage.promoteTemp(temp, ref))
                originalIsRecoverable("3", moved.to)

                // 4 — timestamps
                assertEquals(
                    StorageWriteResult.Written,
                    f.storage.restoreTimestamps(ref, 1_600_000_000_000, 1_600_000_000_000),
                )
                originalIsRecoverable("4", moved.to)

                // 5 — tell the gallery
                assertEquals(StorageWriteResult.Written, f.storage.triggerMediaScan(ref))
                originalIsRecoverable("5", moved.to)

                // The end state: the smaller file at the user's path, the original in the bin.
                assertEquals(5_000, f.storage.sizeBytes(ref))
                assertEquals(10_000, f.storage.sizeBytes(moved.to))

                // And the rollback path still works from here.
                assertEquals(StorageWriteResult.Written, f.storage.deleteWritten(ref))
                assertIs<MoveResult.Moved>(f.storage.moveBack(moved.to, ref))
                assertEquals(
                    originalFingerprint?.sizeBytes,
                    f.storage.fingerprint(ref)?.sizeBytes,
                    "the rollback did not restore the original",
                )
            }
        }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = createFixture()
        try {
            block(fixture)
        } finally {
            fixture.tearDown()
        }
    }
}
