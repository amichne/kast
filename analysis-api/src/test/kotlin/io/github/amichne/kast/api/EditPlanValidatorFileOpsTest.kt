package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.testing.InMemoryFileOperationsFixture
import io.github.amichne.kast.testing.inMemoryFileOperations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for LocalDiskEditApplier file operations using in-memory filesystem (Jimfs).
 * These tests verify CreateFile, DeleteFile, and mixed operations without touching real disk.
 */
class EditPlanValidatorFileOpsTest {
    private lateinit var fixture: InMemoryFileOperationsFixture
    private lateinit var applier: LocalDiskEditApplier

    @BeforeEach
    fun setup() {
        fixture = inMemoryFileOperations()
        applier = LocalDiskEditApplier(fixture.fileOps)
    }

    @Test
    fun `CreateFile creates new file with correct content`() {
        val file = "${fixture.root}New.kt"

        applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file,
                        content = "class New\n",
                    ),
                ),
            ),
        )

        assertTrue(fixture.fileOps.exists(file))
        assertEquals("class New\n", fixture.fileOps.readText(file))
    }

    @Test
    fun `CreateFile fails if file already exists`() {
        val file = "${fixture.root}Existing.kt"
        fixture.createFile(file, "class Existing\n")

        assertThrows(ConflictException::class.java) {
            applier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = file,
                            content = "class Existing\n",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `CreateFile creates parent directories`() {
        val file = "${fixture.root}a/b/c/New.kt"

        applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file,
                        content = "class Nested\n",
                    ),
                ),
            ),
        )

        assertTrue(fixture.fileOps.exists(file))
        assertEquals("class Nested\n", fixture.fileOps.readText(file))
    }

    @Test
    fun `CreateFile with relative path throws ValidationException`() {
        assertThrows(ValidationException::class.java) {
            applier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = "relative/New.kt",
                            content = "class Relative\n",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `DeleteFile removes existing file`() {
        val file = "${fixture.root}DeleteMe.kt"
        val content = "class DeleteMe\n"
        fixture.createFile(file, content)

        applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = file,
                        expectedHash = FileHashing.sha256(content),
                    ),
                ),
            ),
        )

        assertFalse(fixture.fileOps.exists(file))
    }

    @Test
    fun `DeleteFile fails if hash does not match`() {
        val file = "${fixture.root}DeleteMe.kt"
        fixture.createFile(file, "class DeleteMe\n")

        assertThrows(ConflictException::class.java) {
            applier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            filePath = file,
                            expectedHash = "wrong",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `DeleteFile fails if file does not exist`() {
        val file = "${fixture.root}Missing.kt"

        assertThrows(NotFoundException::class.java) {
            applier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            filePath = file,
                            expectedHash = "missing",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `mixed text edits and file operations apply in correct order`() {
        val file = "${fixture.root}Created.kt"
        val createdContent = "class Foo {}\n"

        applier.apply(
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = file,
                        startOffset = createdContent.indexOf('{') + 1,
                        endOffset = createdContent.indexOf('{') + 1,
                        newText = "\n    fun answer() = 42\n",
                    ),
                ),
                fileHashes = listOf(
                    FileHash(
                        filePath = file,
                        hash = FileHashing.sha256(createdContent),
                    ),
                ),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file,
                        content = createdContent,
                    ),
                ),
            ),
        )

        assertEquals(
            """
                class Foo {
                    fun answer() = 42
                }
            """.trimIndent() + "\n",
            fixture.fileOps.readText(file),
        )
    }

    @Test
    fun `CreateFile appears in result createdFiles`() {
        val file = "${fixture.root}Created.kt"

        val result = applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file,
                        content = "class Created\n",
                    ),
                ),
            ),
        )

        assertEquals(listOf(file), result.createdFiles)
    }

    @Test
    fun `DeleteFile appears in result deletedFiles`() {
        val file = "${fixture.root}Deleted.kt"
        val content = "class Deleted\n"
        fixture.createFile(file, content)

        val result = applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = file,
                        expectedHash = FileHashing.sha256(content),
                    ),
                ),
            ),
        )

        assertEquals(listOf(file), result.deletedFiles)
    }
}
