package io.github.amichne.kast.idea.backend.contract.mutation.addition

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.idea.KastIndexerBackendContractTestFixture
import io.github.amichne.kast.idea.waitUntilIndexesAreReady
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
internal class AddDeclarationIntellijProtocolTest : KastIndexerBackendContractTestFixture() {
    private val targetFixture = mainSourceRootFixture.psiFileFixture(
        "AddDeclarationProtocolTarget.kt",
        """
            package demo.protocol

            fun existing(): Int = 1
        """.trimIndent(),
    )
    private val untouchedFixture = mainSourceRootFixture.psiFileFixture(
        "AddDeclarationProtocolUntouched.kt",
        """
            package demo.protocol

            fun untouched(): Int = 2
        """.trimIndent(),
    )

    @Test
    fun `public PSI command changes and saves only the declared target with headless undo unavailable`() = runBlocking {
        val (target, untouched) = protocolFiles()
        val originalTarget = readAction { target.text }
        val originalUntouched = readAction { untouched.text }
        val characterizer = AddDeclarationIntellijProtocolCharacterizer(project)

        val applied = assertInstanceOf(
            AddDeclarationIntellijProtocolResult.Applied::class.java,
            characterizer.execute(
                target,
                "fun characterized( value : kotlin.String ) : kotlin.String = value",
            ),
        )

        assertEquals(setOf(target.virtualFile.path), applied.changedDocumentPaths)
        assertEquals(
            setOf(
                AddDeclarationIntellijProtocolPhase.PREPARED_OUTSIDE_WRITE_COMMAND,
                AddDeclarationIntellijProtocolPhase.COMMAND_ON_EDT_WITH_WRITE_ACCESS,
                AddDeclarationIntellijProtocolPhase.SAVED_OUTSIDE_WRITE_COMMAND,
            ),
            applied.phases,
        )
        assertTrue(applied.commandDuration.nanoseconds > 0L)
        assertFalse(applied.undoAvailable)
        assertFalse(applied.documentUnsavedAfterSave)
        assertTrue(
            readAction { target.text }.contains(
                "fun characterized(value: kotlin.String): kotlin.String = value",
            )
        )
        assertFalse(readAction { target.text }.contains("import kotlin.String"))
        assertFalse(originalTarget == readAction { target.text })
        assertEquals(originalUntouched, readAction { untouched.text })
    }

    @Test
    fun `dumb read-only and cancellation paths never enter the write command`() {
        val (target, _) = protocolFiles()
        val original = runBlocking { readAction { target.text } }
        val characterizer = AddDeclarationIntellijProtocolCharacterizer(project)

        val dumb = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            runBlocking {
                characterizer.execute(target, "class RejectedInDumbMode")
            }
        }
        assertEquals(
            AddDeclarationIntellijProtocolResult.Rejected(
                AddDeclarationIntellijProtocolLimitation.DUMB_MODE,
            ),
            dumb,
        )

        setWritable(target, false)
        try {
            val readOnly = runBlocking {
                characterizer.execute(target, "class RejectedReadOnly")
            }
            assertEquals(
                AddDeclarationIntellijProtocolResult.Rejected(
                    AddDeclarationIntellijProtocolLimitation.READ_ONLY_TARGET,
                ),
                readOnly,
            )
        } finally {
            setWritable(target, true)
        }

        val cancellation = ProcessCanceledException()
        val cancellingCharacterizer = AddDeclarationIntellijProtocolCharacterizer(project) {
            throw cancellation
        }
        val thrown = assertThrows(ProcessCanceledException::class.java) {
            runBlocking { cancellingCharacterizer.execute(target, "class RejectedCancellation") }
        }
        assertSame(cancellation, thrown)

        assertEquals(AddDeclarationIntellijProtocolCommandCount.none(), characterizer.snapshot().commands)
        assertEquals(original, runBlocking { readAction { target.text } })
    }

    @Test
    fun `checked-in ledger matches the running build and executable protocol`() {
        val ledger = Json.parseToJsonElement(ledgerPath().toFile().readText()).jsonObject
        val executor = ledger.getValue("selectedExecutor").jsonObject

        assertEquals(
            ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
            ledger.getValue("runtimeBuild").jsonPrimitive.content,
        )
        assertEquals(
            AddDeclarationIntellijProtocolCharacterizer.SELECTED_PUBLIC_APIS,
            executor.stringList("publicApis"),
        )
        assertEquals(
            AddDeclarationIntellijProtocolCharacterizer.PLAN_INPUTS,
            ledger.stringList("planInputs"),
        )
        assertEquals(
            AddDeclarationIntellijProtocolCharacterizer.FORBIDDEN_INSIDE_COMMAND,
            executor.stringList("forbiddenInsideWriteCommand"),
        )
    }

    private fun protocolFiles(): Pair<KtFile, PsiFile> {
        ensureProjectReady()
        val target = targetFixture.get() as KtFile
        val untouched = untouchedFixture.get()
        waitUntilIndexesAreReady(project)
        return target to untouched
    }

    private fun setWritable(
        file: PsiFile,
        writable: Boolean,
    ) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                file.virtualFile.isWritable = writable
            }
        }
        assertEquals(writable, file.virtualFile.isWritable)
    }

    private fun JsonObject.stringList(key: String): List<String> =
        getValue(key).jsonArray.map { element -> element.jsonPrimitive.content }

    private fun ledgerPath(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
            .map { root -> root.resolve(".agents/arch/kast-add-declaration-intellij-protocol.json") }
            .first { path -> path.toFile().isFile }
}

@Suppress("unused") // IDEA reports test-referenced internal enum entries as unused in batch lint.
internal enum class AddDeclarationIntellijProtocolLimitation {
    DUMB_MODE,
    READ_ONLY_TARGET,
}

@Suppress("unused") // IDEA reports test-referenced internal enum entries as unused in batch lint.
internal enum class AddDeclarationIntellijProtocolPhase {
    PREPARED_OUTSIDE_WRITE_COMMAND,
    COMMAND_ON_EDT_WITH_WRITE_ACCESS,
    SAVED_OUTSIDE_WRITE_COMMAND,
}

@JvmInline
internal value class AddDeclarationIntellijProtocolCommandDuration internal constructor(
    val nanoseconds: Long,
)

@JvmInline
internal value class AddDeclarationIntellijProtocolCommandCount internal constructor(
    val value: Int,
) {
    companion object {
        fun none(): AddDeclarationIntellijProtocolCommandCount =
            AddDeclarationIntellijProtocolCommandCount(0)
    }
}

internal data class AddDeclarationIntellijProtocolSnapshot(
    val commands: AddDeclarationIntellijProtocolCommandCount,
)

internal sealed interface AddDeclarationIntellijProtocolResult {
    data class Applied(
        val changedDocumentPaths: Set<String>,
        val phases: Set<AddDeclarationIntellijProtocolPhase>,
        val commandDuration: AddDeclarationIntellijProtocolCommandDuration,
        val undoAvailable: Boolean,
        val documentUnsavedAfterSave: Boolean,
    ) : AddDeclarationIntellijProtocolResult

    data class Rejected(
        val limitation: AddDeclarationIntellijProtocolLimitation,
    ) : AddDeclarationIntellijProtocolResult
}

internal class AddDeclarationIntellijProtocolCharacterizer(
    private val project: Project,
    private val beforePreparation: () -> Unit = ProgressManager::checkCanceled,
) {
    private val commandEntries = AtomicInteger()

    suspend fun execute(
        target: KtFile,
        declarationText: String,
    ): AddDeclarationIntellijProtocolResult {
        beforePreparation()
        val preparation = readAction {
            check(!ApplicationManager.getApplication().isWriteAccessAllowed)
            ProgressManager.checkCanceled()
            when {
                DumbService.getInstance(project).isDumb ->
                    AddDeclarationIntellijPreparation.Rejected(
                        AddDeclarationIntellijProtocolLimitation.DUMB_MODE,
                    )
                !target.virtualFile.isWritable ->
                    AddDeclarationIntellijPreparation.Rejected(
                        AddDeclarationIntellijProtocolLimitation.READ_ONLY_TARGET,
                    )
                else -> AddDeclarationIntellijPreparation.Ready(
                    target = target,
                    declaration = KtPsiFactory(project, false).createDeclaration(declarationText),
                    document = requireNotNull(
                        FileDocumentManager.getInstance().getDocument(target.virtualFile),
                    ),
                )
            }
        }
        return when (preparation) {
            is AddDeclarationIntellijPreparation.Ready -> apply(preparation)
            is AddDeclarationIntellijPreparation.Rejected ->
                AddDeclarationIntellijProtocolResult.Rejected(preparation.limitation)
        }
    }

    fun snapshot(): AddDeclarationIntellijProtocolSnapshot =
        AddDeclarationIntellijProtocolSnapshot(
            AddDeclarationIntellijProtocolCommandCount(commandEntries.get()),
        )

    private fun apply(
        preparation: AddDeclarationIntellijPreparation.Ready,
    ): AddDeclarationIntellijProtocolResult.Applied {
        val changedPaths = linkedSetOf<String>()
        val phases = linkedSetOf(
            AddDeclarationIntellijProtocolPhase.PREPARED_OUTSIDE_WRITE_COMMAND,
        )
        val listenerLifetime = Disposer.newDisposable("add-declaration-protocol-listener")
        val fileDocuments = FileDocumentManager.getInstance()
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    fileDocuments.getFile(event.document)?.let { file ->
                        changedPaths += file.path
                    }
                }
            },
            listenerLifetime,
        )
        var commandDuration = AddDeclarationIntellijProtocolCommandDuration(0L)
        var undoAvailable = false
        var unsavedAfterSave = true
        try {
            ApplicationManager.getApplication().invokeAndWait {
                val application = ApplicationManager.getApplication()
                val startedAt = System.nanoTime()
                commandEntries.incrementAndGet()
                WriteCommandAction.writeCommandAction(project, preparation.target)
                    .withName(COMMAND_NAME)
                    .withGroupId(COMMAND_GROUP)
                    .compute<KtDeclaration, RuntimeException> {
                        check(application.isDispatchThread)
                        check(application.isWriteAccessAllowed)
                        phases += AddDeclarationIntellijProtocolPhase.COMMAND_ON_EDT_WITH_WRITE_ACCESS
                        val added = preparation.target.add(preparation.declaration) as KtDeclaration
                        val formatted = CodeStyleManager.getInstance(project)
                            .reformat(added, true) as KtDeclaration
                        PsiDocumentManager.getInstance(project).commitDocument(preparation.document)
                        formatted
                    }
                commandDuration = AddDeclarationIntellijProtocolCommandDuration(
                    (System.nanoTime() - startedAt).coerceAtLeast(0L),
                )
                check(!application.isWriteAccessAllowed)
                fileDocuments.saveDocument(preparation.document)
                phases += AddDeclarationIntellijProtocolPhase.SAVED_OUTSIDE_WRITE_COMMAND
                unsavedAfterSave = fileDocuments.isDocumentUnsaved(preparation.document)
                undoAvailable = UndoManager.getInstance(project).isUndoAvailable(null)
            }
        } finally {
            Disposer.dispose(listenerLifetime)
        }
        return AddDeclarationIntellijProtocolResult.Applied(
            changedDocumentPaths = changedPaths.toSet(),
            phases = phases.toSet(),
            commandDuration = commandDuration,
            undoAvailable = undoAvailable,
            documentUnsavedAfterSave = unsavedAfterSave,
        )
    }

    companion object {
        const val COMMAND_NAME = "Kast add declaration"
        const val COMMAND_GROUP = "kast.add-declaration"

        val SELECTED_PUBLIC_APIS = listOf(
            "org.jetbrains.kotlin.psi.KtPsiFactory.createDeclaration",
            "com.intellij.psi.PsiElement.add",
            "com.intellij.psi.codeStyle.CodeStyleManager.reformat(PsiElement,boolean)",
            "com.intellij.openapi.command.WriteCommandAction.writeCommandAction(Project,PsiFile...)",
            "com.intellij.psi.PsiDocumentManager.commitDocument",
            "com.intellij.openapi.fileEditor.FileDocumentManager.saveDocument",
        )

        val PLAN_INPUTS = listOf(
            "canonicalTargetPath",
            "targetPreimageSha256",
            "semanticGeneration",
            "compiledSourceOwner",
            "insertionAnchor",
            "declarationText",
            "expectedPostimageSha256",
            "formatWhitespaceOnly",
            "declaredWriteSet",
        )

        val FORBIDDEN_INSIDE_COMMAND = listOf(
            "INDEX_OR_SEARCH",
            "SMART_MODE_WAIT",
            "VFS_REFRESH",
            "GRADLE_IMPORT",
            "PERSISTENCE",
            "VERIFICATION",
            "DOCUMENT_SAVE",
            "REFERENCE_SHORTENING",
        )
    }
}

private sealed interface AddDeclarationIntellijPreparation {
    data class Ready(
        val target: KtFile,
        val declaration: KtDeclaration,
        val document: Document,
    ) : AddDeclarationIntellijPreparation

    data class Rejected(
        val limitation: AddDeclarationIntellijProtocolLimitation,
    ) : AddDeclarationIntellijPreparation
}
