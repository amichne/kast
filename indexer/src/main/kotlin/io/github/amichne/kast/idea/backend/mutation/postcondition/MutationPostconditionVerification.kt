@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionAuthority
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.CompleteRelationshipCoverageAdmission
import io.github.amichne.kast.idea.backend.relationships.completeRelationshipCoverageAdmission
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.visibility
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal suspend fun KastIndexerBackend.verifyMutationPostconditionOperation(
    query: ParsedMutationPostconditionQuery,
): MutationPostconditionResult = withContext(readDispatcher) {
    telemetry.inSpan(
        IdeaTelemetryScope.VERIFY_MUTATION_POSTCONDITION,
        "kast.idea.verifyMutationPostcondition",
    ) {
        val expectedPostimages = query.authority.expectedPostimages()
        verifyExactPostimages(expectedPostimages)
        val currentGeneration = psiGeneration()
        val evidence = timedReadAction(
            telemetry,
            IdeaTelemetryScope.VERIFY_MUTATION_POSTCONDITION,
            "kast.idea.verifyMutationPostcondition.prove",
        ) {
            verifySemanticPostcondition(query.authority, currentGeneration)
        }
        if (psiGeneration() != currentGeneration) failPostcondition(
            MutationPostconditionLimitation.GENERATION_CHANGED,
            "The semantic generation changed during mutation postcondition verification",
        )
        verifyExactPostimages(expectedPostimages)
        MutationPostconditionResult.verified(
            operation = query.authority.operation(),
            currentGeneration = MutationSemanticGeneration(currentGeneration),
            postimages = expectedPostimages.map { expected ->
                VerifiedMutationPostimage(
                    filePath = ExactFileImagePath(expected.filePath),
                    sha256 = expected.image.sha256,
                )
            }.sortedBy { it.filePath.value },
            evidence = evidence,
        )
    }
}

private data class ExpectedPostimage(val filePath: String, val image: ExactByteImage)

private fun ParsedMutationPostconditionAuthority.expectedPostimages(): List<ExpectedPostimage> = when (this) {
    is ParsedMutationPostconditionAuthority.Rename -> images.map { image ->
        ExpectedPostimage(image.filePath.value, image.postimage)
    }
    is ParsedMutationPostconditionAuthority.Replacement -> images.map { image ->
        ExpectedPostimage(image.filePath.value, image.postimage)
    }
    is ParsedMutationPostconditionAuthority.AddFile -> listOf(
        ExpectedPostimage(proof.targetPath.value, postimage),
    )
    is ParsedMutationPostconditionAuthority.AddDeclaration -> listOf(
        ExpectedPostimage(proof.targetPath.value, image.postimage),
    )
}.sortedBy(ExpectedPostimage::filePath)

private fun ParsedMutationPostconditionAuthority.operation(): MutationPostconditionOperation = when (this) {
    is ParsedMutationPostconditionAuthority.Rename -> MutationPostconditionOperation.RENAME
    is ParsedMutationPostconditionAuthority.Replacement -> MutationPostconditionOperation.REPLACEMENT
    is ParsedMutationPostconditionAuthority.AddFile -> MutationPostconditionOperation.ADD_FILE
    is ParsedMutationPostconditionAuthority.AddDeclaration -> MutationPostconditionOperation.ADD_DECLARATION
}

private fun KastIndexerBackend.verifyExactPostimages(expected: List<ExpectedPostimage>) {
    expected.forEach { postimage ->
        val path = Path.of(postimage.filePath)
        val actual = try {
            exactFileImageMutation.readFileBytes(path, IdeaWorkspaceMutation.TEXT_EDIT)
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            failPostcondition(
                MutationPostconditionLimitation.POSTIMAGE_UNREADABLE,
                "The exact mutation postimage could not be read securely",
            )
        }
        if (!actual.contentEquals(postimage.image.copyBytes()) ||
            FileHashing.sha256(actual) != postimage.image.sha256.value
        ) failPostcondition(
            MutationPostconditionLimitation.POSTIMAGE_MISMATCH,
            "The exact mutation postimage does not match persisted authority",
        )
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: failPostcondition(
            MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
            "The exact mutation postimage is not admitted to the IntelliJ file system",
        )
        val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile)
        if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) failPostcondition(
            MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
            "The mutation postimage has an unsaved IntelliJ document",
        )
        val semanticBytes = try {
            virtualFile.contentsToByteArray()
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            failPostcondition(
                MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
                "The IntelliJ semantic image could not be read",
            )
        }
        if (!semanticBytes.contentEquals(actual)) failPostcondition(
            MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
            "The IntelliJ semantic image does not equal the exact mutation postimage",
        )
    }
}

private fun KastIndexerBackend.verifySemanticPostcondition(
    authority: ParsedMutationPostconditionAuthority,
    generation: Long,
): MutationPostconditionEvidence = try {
    when (authority) {
        is ParsedMutationPostconditionAuthority.Rename -> verifyRename(authority, generation)
        is ParsedMutationPostconditionAuthority.Replacement -> verifyReplacement(authority)
        is ParsedMutationPostconditionAuthority.AddFile -> verifyAddFile(authority)
        is ParsedMutationPostconditionAuthority.AddDeclaration -> verifyAddDeclaration(authority)
    }
} catch (failure: MutationPostconditionFailedException) {
    throw failure
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    failPostcondition(
        MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        "Compiler-backed mutation postcondition evidence could not be completed",
    )
}


internal fun KastIndexerBackend.currentKtFile(filePath: String): KtFile {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(filePath)) ?: failPostcondition(
        MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        "The mutation target is absent from the IntelliJ file system",
    )
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: failPostcondition(
        MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        "The mutation target has no current Kotlin semantic file",
    )
}

internal fun KastIndexerBackend.exactPostimageKtFile(filePath: String): KtFile {
    val contextual = currentKtFile(filePath)
    val bytes = try {
        exactFileImageMutation.readFileBytes(Path.of(filePath), IdeaWorkspaceMutation.TEXT_EDIT)
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        failPostcondition(
            MutationPostconditionLimitation.POSTIMAGE_UNREADABLE,
            "The exact Kotlin postimage could not be read for compiler analysis",
        )
    }
    val normalizedText = bytes.toString(Charsets.UTF_8)
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    return requireExactProjectPostimage(contextual, normalizedText)
}

internal fun requireExactProjectPostimage(current: KtFile, normalizedExactText: String): KtFile {
    if (current.text != normalizedExactText) failPostcondition(
        MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        "The current project Kotlin PSI does not equal the normalized exact mutation postimage",
    )
    return current
}


internal fun failPostcondition(
    limitation: MutationPostconditionLimitation,
    message: String,
): Nothing = throw MutationPostconditionFailedException.of(limitation, message = message)
