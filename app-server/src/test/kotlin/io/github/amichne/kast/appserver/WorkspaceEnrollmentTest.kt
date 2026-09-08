package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class WorkspaceEnrollmentTest {
    @Test fun `concurrent distinct roots never replace an enrolled workspace`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val first = Files.createDirectory(root.resolve("a")); val second = Files.createDirectory(root.resolve("b"))
        val store = WorkspaceEnrollmentStore(root.resolve("state/workspace.json"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first,second).map { candidate -> pool.submit<Refinement<WorkspaceEnrollment.Enrolled,EnrollmentFailure>> { store.enroll(candidate) } }.map { it.get() }
            assertEquals(1,results.count { it is Refinement.Refined })
            val winner = (results.single { it is Refinement.Refined } as Refinement.Refined).value
            assertEquals(winner,(store.read() as EnrollmentRead.Read).enrollment)
            assertFalse(winner.contains(root.toString()))
        } finally { pool.shutdownNow() }
    }
    @Test fun `corrupt enrollment does not become unenrolled or get overwritten`(@TempDir directory: Path) {
        val file = directory.toRealPath().resolve("workspace.json"); Files.writeString(file,"{")
        val store = WorkspaceEnrollmentStore(file)
        assertInstanceOf(EnrollmentRead.Rejected::class.java,store.read())
        assertInstanceOf(Refinement.Rejected::class.java,store.enroll(directory))
        assertEquals("{",Files.readString(file))
    }
}
