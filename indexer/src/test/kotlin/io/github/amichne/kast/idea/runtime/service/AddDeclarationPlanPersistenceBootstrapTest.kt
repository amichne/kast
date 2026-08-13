package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceFailure
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class AddDeclarationPlanPersistenceBootstrapTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `workspace journal is initialized with no retained bootstrap connection`() {
        val database = tempDir.resolve("add-declaration-plans.db")

        assertInstanceOf<AddDeclarationPlanPersistenceBootstrap.Ready>(
            openAddDeclarationPlanPersistence(database),
        )

        assertTrue(Files.isRegularFile(database))
    }

    @Test
    fun `missing workspace cache directory is a closed bootstrap rejection`() {
        val rejected = assertInstanceOf<AddDeclarationPlanPersistenceBootstrap.Rejected>(
            openAddDeclarationPlanPersistence(tempDir.resolve("missing/plans.db")),
        )

        assertEquals(
            AddDeclarationPlanPersistenceFailure.DATABASE_PATH_INVALID,
            rejected.failure,
        )
    }
}
