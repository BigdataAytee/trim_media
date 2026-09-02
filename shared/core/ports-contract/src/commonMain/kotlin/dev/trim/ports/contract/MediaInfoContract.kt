package dev.trim.ports.contract

import dev.trim.model.StorageRef
import dev.trim.ports.MediaInfo
import dev.trim.ports.MediaProbeResult
import dev.trim.ports.Storage
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Header facts, and only header facts. The Scanner runs this over an entire gallery, so
 * the two things that matter are that the numbers are usable and that a file which is not
 * there, or is not a video, produces a *named* result rather than an exception — the scan
 * must be able to keep going.
 */
public abstract class MediaInfoContract {

    /**
     * The implementation supplies its world: a storage it shares with the media info port,
     * a readable video, something that is not a video, and a ref for a file that is absent.
     */
    public interface Fixture : PortFixture {
        public val mediaInfo: MediaInfo
        public val storage: Storage
        public suspend fun readableVideo(): StorageRef
        public suspend fun notAVideo(): StorageRef
        public suspend fun missingFile(): StorageRef
    }

    public abstract fun createFixture(): Fixture

    public fun cases(): List<ContractCase> = listOf(
        case("a readable video yields a usable header") {
            withFixture { fixture ->
                val result = fixture.mediaInfo.probe(fixture.readableVideo())
                assertIs<MediaProbeResult.Readable>(result)
                val header = result.header
                assertTrue(header.durationMs > 0, "duration was ${header.durationMs}")
                assertTrue(header.width > 0 && header.height > 0, "dimensions were 0")
                assertTrue(header.frameRate > 0.0, "frame rate was ${header.frameRate}")
                assertTrue(header.bitrateBps > 0, "bitrate was ${header.bitrateBps}")
                assertTrue(header.bitDepth >= 8, "bit depth was ${header.bitDepth}")
                assertTrue(
                    header.videoTrackCount >= 1,
                    "a readable video reported ${header.videoTrackCount} video tracks",
                )
                assertTrue(header.audioTrackCount >= 0 && header.otherTrackCount >= 0)
            }
        },
        case("the header's fingerprint is the storage port's fingerprint") {
            // Triage measures the file, the Replacer re-checks it before committing, and
            // the two must be talking about the same thing or the safety check is theatre.
            withFixture { fixture ->
                val ref = fixture.readableVideo()
                val result = fixture.mediaInfo.probe(ref)
                assertIs<MediaProbeResult.Readable>(result)
                assertEquals(
                    fixture.storage.fingerprint(ref),
                    result.header.fingerprint,
                    "MediaInfo and Storage disagree about the identity of the same file",
                )
            }
        },
        case("probing the same unchanged file twice gives the same answer") {
            withFixture { fixture ->
                val ref = fixture.readableVideo()
                val first = fixture.mediaInfo.probe(ref)
                val second = fixture.mediaInfo.probe(ref)
                assertIs<MediaProbeResult.Readable>(first)
                assertIs<MediaProbeResult.Readable>(second)
                assertEquals(first.header, second.header)
            }
        },
        case("a missing file is NotFound, not an exception") {
            withFixture { fixture ->
                assertEquals(
                    MediaProbeResult.NotFound,
                    fixture.mediaInfo.probe(fixture.missingFile()),
                    "a file that vanished mid-scan must not stop the scan",
                )
            }
        },
        case("something that is not a video is Unreadable, not an exception") {
            withFixture { fixture ->
                val result = fixture.mediaInfo.probe(fixture.notAVideo())
                assertIs<MediaProbeResult.Unreadable>(result)
                assertTrue(
                    result.detail.isNotBlank(),
                    "an unreadable file must say why; the scan report shows it to the user",
                )
            }
        },
    )

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = createFixture()
        try {
            block(fixture)
        } finally {
            fixture.tearDown()
        }
    }
}
