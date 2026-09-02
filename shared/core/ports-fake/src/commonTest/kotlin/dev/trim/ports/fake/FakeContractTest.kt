package dev.trim.ports.fake

import dev.trim.model.EncodeSetting
import dev.trim.model.FolderId
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.ports.Clock
import dev.trim.ports.EncodedSample
import dev.trim.ports.FrameWindow
import dev.trim.ports.MediaInfo
import dev.trim.ports.Scheduler
import dev.trim.ports.Scorer
import dev.trim.ports.Storage
import dev.trim.ports.Thermal
import dev.trim.ports.Codec
import dev.trim.ports.contract.ClockContract
import dev.trim.ports.contract.CodecContract
import dev.trim.ports.contract.MediaInfoContract
import dev.trim.ports.contract.SchedulerContract
import dev.trim.ports.contract.ScorerContract
import dev.trim.ports.contract.StorageContract
import dev.trim.ports.contract.ThermalContract
import dev.trim.ports.contract.verifyAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Every fake, held to the contract every real implementation will be held to
 * (app-architecture §11).
 *
 * This is what turns the ~90 tests written against fakes elsewhere in the repo from
 * comfort into evidence: the fakes are not merely convenient, they are demonstrably the
 * same *kind of thing* as the ports they stand in for. When `androidApp` arrives, its
 * instrumented tests subclass these same contracts and any divergence between a fake and a
 * real port shows up as a failing clause rather than as a bug on a device.
 */
class FakeClockContractTest {
    @Test
    fun `FakeClock satisfies the Clock contract`() = runTest {
        object : ClockContract() {
            override fun createClock(): Clock = FakeClock()
        }.cases().verifyAll("FakeClock")
    }
}

class FakeThermalContractTest {
    @Test
    fun `FakeThermal satisfies the Thermal contract`() = runTest {
        object : ThermalContract() {
            override fun createThermal(): Thermal = FakeThermal.cool()
        }.cases().verifyAll("FakeThermal")
    }

    @Test
    fun `every scripted thermal scenario still satisfies the contract`() = runTest {
        // A fake that only satisfies the contract in its default configuration would be a
        // fake that stops being evidence the moment a test scripts it.
        val scenarios = mapOf(
            "oscillating" to { FakeThermal.oscillating() },
            "storm" to { FakeThermal.storm() },
            "unsupported API" to { FakeThermal.unsupportedApi() },
        )
        for ((name, factory) in scenarios) {
            object : ThermalContract() {
                override fun createThermal(): Thermal = factory()
            }.cases().verifyAll("FakeThermal ($name)")
        }
    }
}

class FakeSchedulerContractTest {
    @Test
    fun `FakeScheduler satisfies the Scheduler contract`() = runTest {
        object : SchedulerContract() {
            override fun createScheduler(): Scheduler = FakeScheduler()
        }.cases().verifyAll("FakeScheduler")
    }
}

class FakeMediaInfoContractTest {
    @Test
    fun `FakeMediaInfo satisfies the MediaInfo contract`() = runTest {
        object : MediaInfoContract() {
            override fun createFixture() = object : MediaInfoContract.Fixture {
                private val world = FakeWorld()
                override val mediaInfo: MediaInfo get() = world.mediaInfo
                override val storage: Storage get() = world.storage

                override suspend fun readableVideo(): StorageRef =
                    world.addVideo(StorageRef("content://dcim/readable.mp4"))

                override suspend fun notAVideo(): StorageRef {
                    val ref = StorageRef("content://dcim/notes.txt")
                    world.storage.addFile(FolderId("dcim"), ref, bytes = 12)
                    world.mediaInfo.markUnreadable(ref)
                    return ref
                }

                override suspend fun missingFile(): StorageRef =
                    StorageRef("content://dcim/gone.mp4")
            }
        }.cases().verifyAll("FakeMediaInfo")
    }
}

class FakeStorageContractTest {
    @Test
    fun `FakeStorage satisfies the Storage contract`() = runTest {
        object : StorageContract() {
            override fun createFixture() = object : StorageContract.Fixture {
                private val world = FakeWorld()
                override val storage: Storage get() = world.storage
                override val folder = FolderId("dcim")
                private var seeded = 0

                override suspend fun seedVideo(name: String, bytes: Long): StorageRef {
                    val ref = StorageRef("content://dcim/$name")
                    world.storage.addFile(
                        folder = folder,
                        ref = ref,
                        bytes = bytes,
                        // Distinct content, so two files cannot fingerprint alike.
                        hash = "hash-${seeded++}-$name-$bytes",
                    )
                    return ref
                }

                override suspend fun missingRef(): StorageRef =
                    StorageRef("content://dcim/never-existed.mp4")

                override suspend fun seedTemp(temp: TempRef, bytes: Long) {
                    world.storage.writeTemp(temp, bytes)
                }
            }
        }.cases().verifyAll("FakeStorage")
    }
}

class FakeCodecContractTest {
    @Test
    fun `FakeCodec satisfies the Codec contract`() = runTest {
        object : CodecContract() {
            override fun createFixture() = object : CodecContract.Fixture {
                private val world = FakeWorld()
                private val ref = StorageRef("content://dcim/source.mp4")
                private var added = false

                override val codec: Codec get() = world.codec
                override val storage: Storage get() = world.storage

                override suspend fun source(): StorageRef {
                    if (!added) {
                        world.addVideo(ref)
                        added = true
                    }
                    return ref
                }

                override suspend fun sourceDurationMs(): Long = 60_000

                override suspend fun missingSource(): StorageRef =
                    StorageRef("content://dcim/gone.mp4")

                override suspend fun windows(): List<FrameWindow> =
                    listOf(FrameWindow(0, 2_000), FrameWindow(20_000, 2_000))

                override suspend fun createTemp(): TempRef {
                    source()
                    return world.storage.createTemp("contract")
                }
            }
        }.cases().verifyAll("FakeCodec")
    }
}

class FakeScorerContractTest {
    @Test
    fun `FakeScorer satisfies the Scorer contract`() = runTest {
        object : ScorerContract() {
            override fun createFixture() = object : ScorerContract.Fixture {
                private val world = FakeWorld()
                private val ref = StorageRef("content://dcim/source.mp4")
                private var added = false

                override val scorer: Scorer get() = world.scorer

                override suspend fun source(): StorageRef {
                    if (!added) {
                        world.addVideo(ref)
                        added = true
                    }
                    return ref
                }

                override suspend fun windows(): List<FrameWindow> =
                    listOf(FrameWindow(0, 2_000))

                override suspend fun sampleAt(setting: EncodeSetting): EncodedSample {
                    source()
                    val result = world.codec.encodeWindows(ref, setting, windows())
                    return (result as dev.trim.ports.WindowEncodeResult.Encoded).handle
                }

                override suspend fun unknownSample(): EncodedSample = EncodedSample("never-minted")
            }
        }.cases().verifyAll("FakeScorer")
    }
}
