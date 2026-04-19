package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class EditPlanValidatorFileOpsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `CreateFile creates new file with correct content`() {
        val file = tempDir.resolve("New.kt")

        LocalDiskEditApplier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file.toString(),
                        content = "class New\n",
                    ),
                ),
            ),
        )

        assertTrue(file.exists())
        assertEquals("class New\n", file.readText())
    }

    @Test
    fun `CreateFile fails if file already exists`() {
        val file = tempDir.resolve("Existing.kt")
        file.writeText("class Existing\n")

        assertThrows(ConflictException::class.java) {
            LocalDiskEditApplier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = file.toString(),
                            content = "class Existing\n",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `CreateFile creates parent directories`() {
        val file = tempDir.resolve("a/b/c/New.kt")

        LocalDiskEditApplier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file.toString(),
                        content = "class Nested\n",
                    ),
                ),
            ),
        )

        assertTrue(file.exists())
        assertEquals("class Nested\n", file.readText())
    }

    @Test
    fun `CreateFile with relative path throws ValidationException`() {
        assertThrows(ValidationException::class.java) {
            LocalDiskEditApplier.apply(
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
        val file = tempDir.resolve("DeleteMe.kt")
        file.writeText("class DeleteMe\n")

        LocalDiskEditApplier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = file.toString(),
                        expectedHash = FileHashing.sha256(file.readText()),
                    ),
                ),
            ),
        )

        assertFalse(file.exists())
    }

    @Test
    fun `DeleteFile fails if hash does not match`() {
        val file = tempDir.resolve("DeleteMe.kt")
        file.writeText("class DeleteMe\n")

        assertThrows(ConflictException::class.java) {
            LocalDiskEditApplier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            filePath = file.toString(),
                            expectedHash = "wrong",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `DeleteFile fails if file does not exist`() {
        val file = tempDir.resolve("Missing.kt")

        assertThrows(NotFoundException::class.java) {
            LocalDiskEditApplier.apply(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            filePath = file.toString(),
                            expectedHash = "missing",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `mixed text edits and file operations apply in correct order`() {
        val file = tempDir.resolve("Created.kt")
        val createdContent = "class Foo {}\n"

        LocalDiskEditApplier.apply(
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = file.toString(),
                        startOffset = createdContent.indexOf('{') + 1,
                        endOffset = createdContent.indexOf('{') + 1,
                        newText = "\n    fun answer() = 42\n",
                    ),
                ),
                fileHashes = listOf(
                    FileHash(
                        filePath = file.toString(),
                        hash = FileHashing.sha256(createdContent),
                    ),
                ),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file.toString(),
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
            file.readText(),
        )
    }

    @Test
    fun `CreateFile appears in result createdFiles`() {
        val file = tempDir.resolve("Created.kt")

        val result = LocalDiskEditApplier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = file.toString(),
                        content = "class Created\n",
                    ),
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.createdFiles)
    }

    @Test
    fun `DeleteFile appears in result deletedFiles`() {
        val file = tempDir.resolve("Deleted.kt")
        file.writeText("class Deleted\n")

        val result = LocalDiskEditApplier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.DeleteFile(
                        filePath = file.toString(),
                        expectedHash = FileHashing.sha256(file.readText()),
                    ),
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.deletedFiles)
    }
}
