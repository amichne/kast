package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceFailure
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.kernel.Refinement
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

        val ready = assertInstanceOf<AddDeclarationPlanPersistenceBootstrap.Ready>(
            openAddDeclarationPlanPersistence(database),
        )

        val planId = assertInstanceOf<Refinement.Refined<AddDeclarationPlanId>>(
            AddDeclarationPlanId.parse("0".repeat(64)),
        ).value
        assertInstanceOf<LoadAddDeclarationPlanResult.NotFound>(ready.journal.load(planId))
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
