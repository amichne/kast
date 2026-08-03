package io.github.amichne.kast.idea

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.edit.IdeaEditApplier
import io.github.amichne.kast.idea.mutation.SecureWorkspaceMutation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
internal class IdeaEditApplicationConflictTest : IdeaEditApplicationTestFixture() {
    @Test
    fun `file scoped mutation reports a typed conflict when a concurrent final entry blocks restoration`() = runBlocking {
        ensureProjectReady()

        val workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val target = workspaceRoot.resolve("ConcurrentEdit.kt")
        val original = "package demo\n\nfun value(): Int = 1\n"
        val concurrent = "package demo\n\nfun concurrent(): Int = 9\n"
        Files.writeString(target, original)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)

        val failure = runCatching {
            IdeaEditApplier(
                project = project,
                workspaceRoot = workspaceRoot,
                secureWorkspaceMutation = SecureWorkspaceMutation(
                    workspaceRoot = workspaceRoot,
                    afterTargetDetached = { detachedTarget, mutation ->
                        if (detachedTarget == target && mutation == IdeaWorkspaceMutation.TEXT_EDIT) {
                            Files.writeString(target, concurrent)
                        }
                    },
                ),
            ).apply(
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = target.toString(),
                            startOffset = original.indexOf('1'),
                            endOffset = original.indexOf('1') + 1,
                            newText = "2",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            target.toString(),
                            io.github.amichne.kast.api.validation.FileHashing.sha256(original),
                        ),
                    ),
                    fileOperations = emptyList(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is ConflictException,
            "Expected ConflictException, got ${failure?.let { it::class.qualifiedName } ?: "success"}",
        )
        val conflict = failure as ConflictException
        assertEquals("quarantined", conflict.details["restoration"])
        assertEquals(concurrent, Files.readString(target))
        assertEquals(original, Files.readString(Path.of(conflict.details.getValue("recoveryFilePath"))))
    }
}

internal fun mutationScratch(target: Path, attemptId: String, transitionIndex: Int): MutationScratchSet {
    val parent = requireNotNull(target.parent)
    return MutationScratchSet(
        targetFilePath = target.toString(),
        quarantinePath = parent.resolve(".kast-quarantine-$attemptId-$transitionIndex").toString(),
        preparedPath = parent.resolve(".kast-prepared-$attemptId-$transitionIndex.tmp").toString(),
        preparedCleanupPath = parent.resolve(".kast-cleanup-$attemptId-$transitionIndex-prepared").toString(),
        quarantineCleanupPath = parent.resolve(".kast-cleanup-$attemptId-$transitionIndex-quarantine").toString(),
    )
}
