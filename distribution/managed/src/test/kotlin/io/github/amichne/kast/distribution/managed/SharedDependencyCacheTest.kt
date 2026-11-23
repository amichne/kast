package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SharedDependencyCacheTest {
    @Test
    fun `only a supported settled write state becomes a generation`() {
        assertEquals(
            Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_WRITE_STATE),
            GradleCacheGeneration.parse(byteArrayOf()),
        )
        assertEquals(
            Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_WRITE_STATE),
            GradleCacheGeneration.parse(state(1).copyOf(16)),
        )
        assertEquals(
            Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_WRITE_STATE),
            GradleCacheGeneration.parse(state(1, version = 4)),
        )
        assertEquals(
            Refinement.Rejected(DependencyCacheRejection.WRITE_IN_PROGRESS),
            GradleCacheGeneration.parse(state(0)),
        )
        assertEquals(
            Refinement.Rejected(DependencyCacheRejection.UNSUPPORTED_WRITE_STATE),
            GradleCacheGeneration.parse(state(-1)),
        )
        assertEquals(generation(7), generation(7))
        assertNotEquals(generation(7), generation(8))
        assertNotEquals(generation(7), generation(7, creation = 2), "A recreated cache can repeat a sequence")
    }

    @Test
    fun `a settled cache is copied while a Gradle daemon holds its exclusive lock`(@TempDir directory: Path) {
        val case = Case(directory)
        val donor = case.source("donor", state(7))
        FileChannel.open(donor.resolve("modules-2.lock"), WRITE).use { channel ->
            channel.lock(0, 17, false).use {
                assertEquals(
                    SharedDependencyCacheOutcome.Published(DependencyCacheSource.USER_GRADLE),
                    case.prepare(donor),
                )
            }
        }
        assertEquals("sample", Files.readString(case.snapshot.resolve("modules-2/files-2.1/sample.jar")))
        assertFalse(Files.exists(case.snapshot.resolve("modules-2/modules-2.lock")))
        assertFalse(Files.exists(case.snapshot.resolve("modules-2/gc.properties")))
    }

    @Test
    fun `a write that settles during the copy publishes nothing and leaves no partial copy`(@TempDir directory: Path) {
        val case = Case(directory)
        val donor = case.source("donor", state(7))
        val outcome =
            case.prepare(donor) { source, target ->
                copyModuleFiles(source, target, MAXIMUM_ENTRIES).also {
                    Files.write(source.resolve("modules-2.lock"), state(8))
                }
            }
        assertEquals(skipped(DependencyCacheRejection.CHANGED_DURING_COPY), outcome)
        case.assertNothingPublished()
    }

    @Test
    fun `a write that starts during the copy publishes nothing`(@TempDir directory: Path) {
        val case = Case(directory)
        val donor = case.source("donor", state(7))
        val outcome =
            case.prepare(donor) { source, target ->
                copyModuleFiles(source, target, MAXIMUM_ENTRIES).also {
                    Files.write(source.resolve("modules-2.lock"), state(0))
                }
            }
        assertEquals(skipped(DependencyCacheRejection.WRITE_IN_PROGRESS), outcome)
        case.assertNothingPublished()
    }

    @Test
    fun `an unsettled source is rejected before any copy effect`(@TempDir directory: Path) {
        val case = Case(directory)
        val donor = case.source("donor", state(0))
        val outcome = case.prepare(donor) { _, _ -> error("An unsettled source must not be copied") }
        assertEquals(skipped(DependencyCacheRejection.WRITE_IN_PROGRESS), outcome)
        case.assertNothingPublished()
    }

    @Test
    fun `each rejected source keeps its own reason and a later attempt can publish`(@TempDir directory: Path) {
        val case = Case(directory)
        assertEquals(
            SharedDependencyCacheOutcome.Skipped(
                listOf(
                    RejectedDependencyCache(
                        DependencyCacheSource.SHARED_GRADLE,
                        DependencyCacheRejection.SOURCE_UNAVAILABLE,
                    )
                )
            ),
            case.prepare(null),
        )
        val empty = Files.createDirectories(case.home.resolve("caches/modules-2/files-2.1")).parent
        Files.write(empty.resolve("modules-2.lock"), state(1))
        val donor = case.source("donor", byteArrayOf())
        assertEquals(
            SharedDependencyCacheOutcome.Skipped(
                listOf(
                    RejectedDependencyCache(DependencyCacheSource.SHARED_GRADLE, DependencyCacheRejection.NO_ARTIFACTS),
                    RejectedDependencyCache(
                        DependencyCacheSource.USER_GRADLE,
                        DependencyCacheRejection.UNSUPPORTED_WRITE_STATE,
                    ),
                )
            ),
            case.prepare(donor),
        )
        Files.write(donor.resolve("modules-2.lock"), state(1))
        assertInstanceOf(SharedDependencyCacheOutcome.Published::class.java, case.prepare(donor))
        assertEquals(SharedDependencyCacheOutcome.AlreadyPublished, case.prepare(donor))
    }

    @Test
    fun `capacity links and filesystem failures stay distinct`(@TempDir directory: Path) {
        val case = Case(directory)
        val donor = case.source("donor", state(7))
        assertEquals(
            skipped(DependencyCacheRejection.CAPACITY_EXCEEDED),
            case.prepare(donor) { source, target -> copyModuleFiles(source, target, 1) },
        )
        val failures = mutableListOf<IOException>()
        val failure = IOException("copy")
        assertEquals(
            skipped(DependencyCacheRejection.FILESYSTEM_REJECTED),
            case.prepare(donor, failures::add) { _, _ -> throw failure },
        )
        assertEquals(listOf<IOException>(failure), failures)
        Files.createSymbolicLink(donor.resolve("files-2.1/link.jar"), donor.resolve("files-2.1/sample.jar"))
        assertEquals(skipped(DependencyCacheRejection.UNSUPPORTED_ENTRY), case.prepare(donor))
        case.assertNothingPublished()
    }

    @Test
    fun `outcomes encode a closed path-free document`() {
        val published = encoded(SharedDependencyCacheOutcome.Published(DependencyCacheSource.USER_GRADLE))
        assertEquals(setOf("type", "source"), published.keys)
        assertEquals("PUBLISHED", published.text("type"))
        assertEquals("USER_GRADLE", published.text("source"))
        for ((outcome, type) in
            listOf(
                SharedDependencyCacheOutcome.AlreadyPublished to "ALREADY_PUBLISHED",
                SharedDependencyCacheOutcome.CreatorBusy to "CREATOR_BUSY",
            )) {
            assertEquals(setOf("type"), encoded(outcome).keys)
            assertEquals(type, encoded(outcome).text("type"))
        }
        val rejectionNames =
            setOf(
                "SOURCE_UNAVAILABLE",
                "UNSUPPORTED_WRITE_STATE",
                "WRITE_IN_PROGRESS",
                "CHANGED_DURING_COPY",
                "CAPACITY_EXCEEDED",
                "UNSUPPORTED_ENTRY",
                "NO_ARTIFACTS",
                "FILESYSTEM_REJECTED",
            )
        val encodedNames =
            DependencyCacheRejection.entries.map { reason ->
                val skipped = encoded(skipped(reason))
                assertEquals(setOf("type", "rejected"), skipped.keys)
                assertEquals("SKIPPED", skipped.text("type"))
                val user = skipped.getValue("rejected").jsonArray[1].jsonObject
                assertEquals(setOf("source", "reason"), user.keys)
                assertEquals("USER_GRADLE", user.text("source"))
                user.text("reason")
            }
        assertEquals(rejectionNames, encodedNames.toSet())
    }

    private fun encoded(outcome: SharedDependencyCacheOutcome) =
        Json.encodeToJsonElement(SharedDependencyCacheOutcome.serializer(), outcome).jsonObject

    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content

    private class Case(directory: Path) {
        private val root = directory.toRealPath()
        val home: Path = Files.createDirectory(root.resolve("shared"))
        val snapshot: Path = home.resolve("kast-read-only")
        private val staging = Files.createDirectory(root.resolve("staging"))

        fun source(name: String, state: ByteArray): Path {
            val source = Files.createDirectory(root.resolve(name))
            Files.write(source.resolve("modules-2.lock"), state)
            Files.writeString(source.resolve("gc.properties"), "")
            Files.writeString(Files.createDirectory(source.resolve("files-2.1")).resolve("sample.jar"), "sample")
            return source
        }

        fun prepare(
            donor: Path?,
            report: (IOException) -> Unit = { throw it },
            copyFiles: DependencyCacheCopy = DependencyCacheCopy { source, target ->
                copyModuleFiles(source, target, MAXIMUM_ENTRIES)
            },
        ) = prepareSharedDependencyCache(home, staging, donor, report, copyFiles)

        fun assertNothingPublished() {
            assertFalse(Files.exists(snapshot))
            assertFalse(Files.exists(staging.resolve("dependency-snapshot")))
        }
    }

    private fun skipped(reason: DependencyCacheRejection) =
        SharedDependencyCacheOutcome.Skipped(
            listOf(
                RejectedDependencyCache(
                    DependencyCacheSource.SHARED_GRADLE,
                    DependencyCacheRejection.SOURCE_UNAVAILABLE,
                ),
                RejectedDependencyCache(DependencyCacheSource.USER_GRADLE, reason),
            )
        )

    private fun generation(sequence: Long, creation: Long = 1) =
        (GradleCacheGeneration.parse(state(sequence, creation = creation)) as Refinement.Refined).value

    /** The seventeen-byte state region that Gradle's DefaultLockStateSerializer writes. */
    private fun state(sequence: Long, creation: Long = 1, version: Byte = 3): ByteArray =
        ByteBuffer.allocate(17).put(version).putLong(creation).putLong(sequence).array()
}

private const val MAXIMUM_ENTRIES = 1024L
