package dev.trim.ports.fake

import dev.trim.model.FolderId
import dev.trim.model.StorageRef
import dev.trim.ports.MediaHeader

/**
 * All seven fakes wired to one clock and one content library, so a test builds a coherent
 * world in a few lines instead of assembling seven objects that might disagree.
 */
public class FakeWorld(startEpochMs: Long = 1_700_000_000_000) {
    public val clock: FakeClock = FakeClock(startEpochMs)
    public val library: FakeContentLibrary = FakeContentLibrary()
    public val storage: FakeStorage = FakeStorage(clock)
    public val mediaInfo: FakeMediaInfo = FakeMediaInfo(storage, clock)
    public val codec: FakeCodec = FakeCodec(library, storage, clock)
    public val scorer: FakeScorer = FakeScorer(library, clock)
    public val thermal: FakeThermal = FakeThermal.cool()
    public val scheduler: FakeScheduler = FakeScheduler()

    /** Adds a file that exists in storage, has a header, and has content the codec knows. */
    public fun addVideo(
        ref: StorageRef,
        folder: FolderId = FolderId("dcim"),
        sizeBytes: Long = 400L * 1024 * 1024,
        header: MediaHeader = FakeMediaInfo.header(),
        content: ContentModel = ContentModel.linear(),
        lastModifiedEpochMs: Long = clock.nowEpochMs(),
    ): StorageRef {
        storage.addFile(
            folder = folder,
            ref = ref,
            bytes = sizeBytes,
            lastModifiedEpochMs = lastModifiedEpochMs,
        )
        mediaInfo.register(ref, header)
        library.register(ref, content)
        return ref
    }
}
