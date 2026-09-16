package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceEnrollmentTest {
    @Test
    fun `automatic registration reports persisted success and exact write failure`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))
        val store = WorkspaceEnrollmentStore(root.resolve("config/workspaces.json"))
        val enrollment = (store.read() as EnrollmentRead.Read).enrollment
        val observations = mutableListOf<WorkspaceStartupObservation>()
        assertInstanceOf(
            WorkspaceSelection.Selected::class.java,
            enrollment.selectForStart(first.toString(), observe = observations::add),
        )
        val lock = root.resolve("config/workspaces.json.lock")
        Files.delete(lock)
        Files.createSymbolicLink(lock, root.resolve("foreign"))
        assertEquals(
            WorkspaceSelectionFailure.REGISTRATION_WRITE_REJECTED,
            (enrollment.selectForStart(second.toString(), observe = observations::add) as WorkspaceSelection.Rejected)
                .failure,
        )
        assertEquals(
            listOf(
                WorkspaceStartupOutcome.Registered,
                WorkspaceStartupOutcome.Rejected(EnrollmentFailure.WRITE_REJECTED),
            ),
            observations.map { it.outcome },
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded =
            json.parseToJsonElement(json.encodeToString(WorkspaceStartupObservation.serializer(), observations.last()))
        val document = encoded as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("event", "outcome"), document.keys)
        assertEquals(
            "kast_workspace_registration",
            (document.getValue("event") as kotlinx.serialization.json.JsonPrimitive).content,
        )
        val outcome = document.getValue("outcome") as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("type", "failure"), outcome.keys)
        assertEquals("rejected", (outcome.getValue("type") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(
            "WRITE_REJECTED",
            (outcome.getValue("failure") as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    fun `thread admission registers once and read only selection never registers`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val child = Files.createDirectory(workspace.resolve("child"))
        val store = WorkspaceEnrollmentStore(root.resolve("config/workspaces.json"))
        val enrollment = (store.read() as EnrollmentRead.Read).enrollment
        assertInstanceOf(WorkspaceSelection.Rejected::class.java, enrollment.select(workspace.toString()))
        assertEquals(0, (store.snapshot() as WorkspaceRegistryRead.Read).snapshot.workspaces.size)
        repeat(2) {
            val selected =
                enrollment.selectForStart(child.toString(), workspace.toString()) as WorkspaceSelection.Selected
            assertEquals(workspace, selected.workspace.root.path)
        }
        assertEquals(1, (store.snapshot() as WorkspaceRegistryRead.Read).snapshot.revision.value)
        assertInstanceOf(WorkspaceSelection.Selected::class.java, enrollment.select(child.toString()))
        assertEquals(
            WorkspaceSelectionFailure.WORKING_DIRECTORY_OUTSIDE_ROOT,
            (enrollment.selectForStart(root.toString(), child.toString()) as WorkspaceSelection.Rejected).failure,
        )
        assertEquals(1, (store.snapshot() as WorkspaceRegistryRead.Read).snapshot.workspaces.size)
        Files.writeString(root.resolve("config/workspaces.json"), "broken registry")
        assertInstanceOf(WorkspaceSelection.Rejected::class.java, enrollment.selectForStart(root.toString()))
        assertEquals("broken registry", Files.readString(root.resolve("config/workspaces.json")))
    }

    @Test
    fun `live registry rejects a replaced parent without reading foreign registrations`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val first = Files.createDirectory(root.resolve("a"))
        val second = Files.createDirectory(root.resolve("b"))
        val parent = root.resolve("config")
        val store = WorkspaceEnrollmentStore(parent.resolve("workspaces.json"))
        assertInstanceOf(Refinement.Refined::class.java, store.enroll(first))
        val live = (store.read() as EnrollmentRead.Read).enrollment
        val foreign = Files.createDirectory(root.resolve("foreign"))
        val foreignDocument = """{"schemaVersion":2,"revision":2,"roots":["$second"]}"""
        Files.writeString(foreign.resolve("workspaces.json"), foreignDocument)
        Files.move(parent, root.resolve("held-config"))
        Files.createSymbolicLink(parent, foreign)
        assertInstanceOf(WorkspaceSelection.Rejected::class.java, live.select(second.toString()))
        assertEquals(foreignDocument, Files.readString(foreign.resolve("workspaces.json")))
    }

    @Test
    fun `concurrent distinct roots remain registered`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val first = Files.createDirectory(root.resolve("a"))
        val second = Files.createDirectory(root.resolve("b"))
        val store = WorkspaceEnrollmentStore(root.resolve("state/workspace.json"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(first, second)
                    .map { candidate ->
                        pool.submit<Refinement<WorkspaceRegistrationAcknowledgement, EnrollmentFailure>> {
                            store.enroll(candidate)
                        }
                    }
                    .map { it.get() }
            assertEquals(2, results.count { it is Refinement.Refined })
            val enrolled = (store.read() as EnrollmentRead.Read).enrollment
            assertTrue(enrolled.contains(first.toString()))
            assertTrue(enrolled.contains(second.toString()))
            assertFalse(enrolled.contains(root.toString()))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `corrupt enrollment does not become unenrolled or get overwritten`(@TempDir directory: Path) {
        val file = directory.toRealPath().resolve("workspace.json")
        Files.writeString(file, "{")
        val store = WorkspaceEnrollmentStore(file)
        assertInstanceOf(EnrollmentRead.Rejected::class.java, store.read())
        assertInstanceOf(Refinement.Rejected::class.java, store.enroll(directory))
        assertEquals("{", Files.readString(file))
    }

    @Test
    fun `live registrations coalesce root aliases and require explicit overlap selection`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val parent = Files.createDirectory(root.resolve("parent"))
        val child = Files.createDirectory(parent.resolve("child"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), child)
        val store = WorkspaceEnrollmentStore(root.resolve("state/workspaces.json"))
        val live = (store.read() as EnrollmentRead.Read).enrollment
        assertEquals(
            WorkspaceSelectionFailure.UNREGISTERED,
            (live.select(child.toString()) as WorkspaceSelection.Rejected).failure,
        )
        store.enroll(parent)
        assertEquals(parent, (live.select(child.toString()) as WorkspaceSelection.Selected).workspace.root.path)
        store.enroll(child)
        store.enroll(alias)
        val snapshot = (store.snapshot() as WorkspaceRegistryRead.Read).snapshot
        assertEquals(2, snapshot.workspaces.size)
        assertEquals(2, snapshot.revision.value)
        assertEquals(
            WorkspaceSelectionFailure.AMBIGUOUS,
            (live.select(alias.toString()) as WorkspaceSelection.Rejected).failure,
        )
        val explicit = live.select(alias.toString(), child.toString()) as WorkspaceSelection.Selected
        assertEquals(child, explicit.workspace.root.path)
        assertEquals(child, explicit.workingDirectory.path)
        assertEquals(
            WorkspaceSelectionFailure.WORKING_DIRECTORY_OUTSIDE_ROOT,
            (live.select(parent.toString(), child.toString()) as WorkspaceSelection.Rejected).failure,
        )
        assertInstanceOf(WorkspaceSelection.Rejected::class.java, live.select(null))
    }
}
