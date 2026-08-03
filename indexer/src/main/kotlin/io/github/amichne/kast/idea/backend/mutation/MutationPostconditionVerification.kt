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

private fun KastIndexerBackend.verifyRename(
    authority: ParsedMutationPostconditionAuthority.Rename,
    generation: Long,
): MutationPostconditionEvidence.Rename {
    val adjusted = adjustedRenameRanges(authority.edits)
    val declarationEdit = authority.edits.singleOrNull { edit ->
        edit.filePath == authority.proof.target.declarationFile.value &&
            edit.startOffset == authority.proof.target.declarationStartOffset.value
    } ?: failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "Persisted rename authority has no exact declaration edit",
    )
    val declarationRange = adjusted.getValue(declarationEdit.rangeKey())
    val file = currentKtFile(declarationEdit.filePath)
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .singleOrNull { declaration ->
            declaration.nameIdentifier?.textRange?.let { range ->
                range.startOffset == declarationRange.first && range.endOffset == declarationRange.last + 1
            } == true
        } ?: failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The renamed declaration is not present at its exact adjusted range",
    )
    val resultingTarget = compilerSourceIdentity(target)
    val expectedTarget = authority.proof.target.copy(
        fqName = renamedFqName(authority.proof.target.fqName, declarationEdit.newText),
        declarationStartOffset = NonNegativeInt(declarationRange.first),
    )
    if (resultingTarget != expectedTarget) failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The renamed declaration does not have the exact expected compiler identity",
    )

    val (searchScope, _) = visibilityScopedSearch(target, target.visibility())
    val rawReferences = mutableListOf<PsiReference>()
    ReferencesSearch.search(target, searchScope).forEach(
        object : Processor<PsiReference> {
            override fun process(reference: PsiReference): Boolean {
                ProgressManager.checkCanceled()
                rawReferences.add(reference)
                return true
            }
        },
    )
    val occurrences = rawReferences.map { reference ->
        val occurrence = reference.toReferenceOccurrence(includeUsageSiteScope = false)
            ?: failPostcondition(
                MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
                "A renamed occurrence lost exact compiler source evidence",
            )
        if (occurrence.containingSymbol is ContainingSymbolEvidence.Unavailable) failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence lost compiler-visible containing-symbol evidence",
        )
        val resolved = reference.resolve() ?: failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence no longer resolves",
        )
        val resolvedFile = resolved.containingFile as? KtFile ?: failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence no longer resolves to Kotlin source",
        )
        val identity = analyze(resolvedFile) {
            resolved.toSymbolModel(compilerContainingDeclarationName(resolved))
        }.relationshipIdentity()
        if (identity != resultingTarget) failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence resolves to a different compiler target",
        )
        ExactRenameOccurrence(occurrence, identity, RenameOccurrenceProvenance.COMPILER)
    }.distinctBy { occurrence -> occurrence.reference.location.rangeKey() }
        .sortedWith(compareBy(
            { it.reference.location.filePath },
            { it.reference.location.startOffset },
            { it.reference.location.endOffset },
        ))

    val expectedRanges = authority.proof.occurrences.map { occurrence ->
        val oldRange = occurrence.reference.location.rangeKey()
        val edit = authority.edits.singleOrNull { candidate -> candidate.rangeKey() == oldRange }
            ?: failPostcondition(
                MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
                "Persisted rename authority dropped one proven occurrence edit",
            )
        val currentRange = adjusted.getValue(edit.rangeKey())
        Triple(edit.filePath, currentRange.first, currentRange.last + 1)
    }.toSet()
    val currentRanges = occurrences.map { it.reference.location.rangeKey() }.toSet()
    if (currentRanges != expectedRanges || occurrences.size != authority.proof.occurrences.size) failPostcondition(
        MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
        "The renamed reference set or exact cardinality changed",
    )

    val rootKind = resultingTarget.kind.renameRelationshipRootKind() ?: failPostcondition(
        MutationPostconditionLimitation.REFERENCE_COVERAGE_INCOMPLETE,
        "The renamed target kind cannot prove complete relationship coverage",
    )
    val admission = completeRelationshipCoverageAdmission(
        selector = KastExactSymbolSelector(
            fqName = resultingTarget.fqName,
            declarationFile = resultingTarget.declarationFile.value,
            declarationStartOffset = resultingTarget.declarationStartOffset.value,
            kind = resultingTarget.kind,
            containingType = resultingTarget.containingType,
        ),
        rootKind = rootKind,
        searchScope = searchScope,
        requiredGeneration = generation,
        knownMinimumCount = occurrences.size,
    )
    val coverage = when (admission) {
        is CompleteRelationshipCoverageAdmission.Limited -> failPostcondition(
            MutationPostconditionLimitation.REFERENCE_COVERAGE_INCOMPLETE,
            "Complete renamed reference-family coverage could not be reproven",
        )
        is CompleteRelationshipCoverageAdmission.Proven -> admission.coverage
    }
    return MutationPostconditionEvidence.Rename(
        resultingTarget = resultingTarget,
        evidence = RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(occurrences.size),
            coverage = coverage,
        ),
        occurrences = occurrences,
    )
}

private fun adjustedRenameRanges(edits: List<TextEdit>): Map<Triple<String, Int, Int>, IntRange> = buildMap {
    edits.groupBy(TextEdit::filePath).forEach { (_, fileEdits) ->
        var delta = 0
        fileEdits.sortedBy(TextEdit::startOffset).forEach { edit ->
            val start = edit.startOffset + delta
            put(edit.rangeKey(), start until start + edit.newText.length)
            delta += edit.newText.length - (edit.endOffset - edit.startOffset)
        }
    }
}

private fun renamedFqName(old: String, newName: String): String =
    old.substringBeforeLast('.', missingDelimiterValue = "")
        .let { owner -> if (owner.isEmpty()) newName else "$owner.$newName" }

private fun KastIndexerBackend.verifyReplacement(
    authority: ParsedMutationPostconditionAuthority.Replacement,
): MutationPostconditionEvidence.Replacement {
    val file = exactPostimageKtFile(authority.edit.filePath)
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .singleOrNull { declaration ->
            declaration.textRange.startOffset == authority.edit.startOffset &&
                declaration.textRange.endOffset == authority.edit.startOffset + authority.edit.newText.length
        } ?: failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The replacement declaration is not present at its exact resulting range",
    )
    if (target.text != authority.edit.newText) failPostcondition(
        MutationPostconditionLimitation.POSTIMAGE_MISMATCH,
        "The semantic replacement declaration does not equal its exact persisted edit",
    )
    val compilerTarget = compilerSourceIdentity(target)
    val expected = authority.proof.target
    if (compilerTarget.fqName != expected.fqName || compilerTarget.kind != expected.kind ||
        compilerTarget.containingType != expected.containingType
    ) failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The replacement changed its compiler FQ owner or declaration kind",
    )
    val resultingTarget = compilerTarget.copy(declarationFile = expected.declarationFile)
    val signature = try {
        compilerReplacementSignature(target)
    } catch (failure: ReplacementProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.SIGNATURE_MISMATCH,
            "The resulting replacement compiler signature is incomplete",
        )
    }
    if (signature != authority.proof.proposedSignature) failPostcondition(
        MutationPostconditionLimitation.SIGNATURE_MISMATCH,
        "The resulting replacement compiler signature changed",
    )
    val outbound = try {
        collectExactOutboundReferences(
            originalFile = file,
            syntheticFile = file,
            proposed = target,
            replacementStartOffset = authority.edit.startOffset,
            originalDeclarationLength = authority.edit.newText.length,
            proposedDeclarationText = authority.edit.newText,
        )
    } catch (failure: ReplacementProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
            "The resulting replacement outbound proof is incomplete",
        )
    }
    if (outbound != authority.proof.outboundReferences) failPostcondition(
        MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
        "The resulting replacement outbound occurrence set changed",
    )
    return MutationPostconditionEvidence.Replacement(
        resultingTarget = resultingTarget,
        sourceRange = target.toKastLocation(target.textRange),
        signature = signature,
        outboundEvidence = ReplacementOutboundEvidence.Complete.of(outbound.size),
        outboundReferences = outbound,
    )
}

private fun KastIndexerBackend.verifyAddFile(
    authority: ParsedMutationPostconditionAuthority.AddFile,
): MutationPostconditionEvidence.AddFile = verifyAddition(
    targetPath = authority.proof.targetPath.value,
    expectedOwner = authority.proof.owner,
    expectedContext = authority.proof.context,
    expectedPackage = authority.proof.packageIdentity,
    expectedDeclarations = authority.proof.declarations,
    expectedOutbound = authority.proof.outboundEvidence,
    relativeBaseOffset = 0,
    expectedContextPaths = authority.proof.context.contextFileHashes.map { it.filePath }.toSet() +
        authority.proof.targetPath.value,
    targetContextMayDiffer = true,
    provenFileBottomOffset = null,
).let { parts ->
    MutationPostconditionEvidence.AddFile(
        owner = authority.proof.owner,
        packageIdentity = authority.proof.packageIdentity,
        declarations = parts.declarations,
        outboundEvidence = parts.outbound,
    )
}

private fun KastIndexerBackend.verifyAddDeclaration(
    authority: ParsedMutationPostconditionAuthority.AddDeclaration,
): MutationPostconditionEvidence.AddDeclaration {
    val normalizedPreimage = authority.image.preimage.copyBytes().toString(Charsets.UTF_8)
        .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val separatorLength = when {
        normalizedPreimage.isEmpty() || normalizedPreimage.endsWith("\n\n") -> 0
        normalizedPreimage.endsWith('\n') -> 1
        else -> 2
    }
    val relativeBase = authority.proof.insertion.offset.value + separatorLength
    val parts = verifyAddition(
        targetPath = authority.proof.targetPath.value,
        expectedOwner = authority.proof.owner,
        expectedContext = authority.proof.context,
        expectedPackage = authority.proof.packageIdentity,
        expectedDeclarations = listOf(authority.proof.declaration),
        expectedOutbound = authority.proof.outboundEvidence,
        relativeBaseOffset = relativeBase,
        expectedContextPaths = authority.proof.context.contextFileHashes.map { it.filePath }.toSet(),
        targetContextMayDiffer = true,
        provenFileBottomOffset = authority.proof.insertion.offset.value,
    )
    return MutationPostconditionEvidence.AddDeclaration(
        owner = authority.proof.owner,
        packageIdentity = authority.proof.packageIdentity,
        declaration = parts.declarations.single(),
        outboundEvidence = parts.outbound,
    )
}

private fun KastIndexerBackend.verifyAddition(
    targetPath: String,
    expectedOwner: AdditionSourceOwner,
    expectedContext: ExactAdditionProofContext,
    expectedPackage: AdditionKotlinPackage,
    expectedDeclarations: List<AdditionTopLevelDeclaration>,
    expectedOutbound: ExactAdditionOutboundEvidence,
    relativeBaseOffset: Int,
    expectedContextPaths: Set<String>,
    targetContextMayDiffer: Boolean,
    provenFileBottomOffset: Int?,
): AdditionProofParts {
    val target = Path.of(targetPath)
    val currentOwner = try {
        exactAdditionOwner(target)
    } catch (failure: AdditionProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.SOURCE_OWNER_CHANGED,
            "The addition source owner can no longer be proven",
        )
    }
    if (currentOwner.owner != expectedOwner) failPostcondition(
        MutationPostconditionLimitation.SOURCE_OWNER_CHANGED,
        "The exact addition source owner changed",
    )
    if (currentOwner.modelFingerprint != expectedContext.projectModelFingerprint) failPostcondition(
        MutationPostconditionLimitation.PROJECT_MODEL_CHANGED,
        "The exact addition Gradle project model changed",
    )
    if (currentOwner.classpathFingerprint != expectedContext.classpathFingerprint) failPostcondition(
        MutationPostconditionLimitation.CLASSPATH_CHANGED,
        "The exact addition compiler classpath changed",
    )
    val currentPaths = currentOwner.sourceFiles.map(Path::toString).toSet()
    if (currentPaths != expectedContextPaths) failPostcondition(
        MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
        "The exact model-owned source file set changed",
    )
    expectedContext.contextFileHashes.forEach { expected ->
        if (targetContextMayDiffer && expected.filePath == targetPath) return@forEach
        val actual = try {
            exactFileImageMutation.readFileBytes(Path.of(expected.filePath), IdeaWorkspaceMutation.TEXT_EDIT)
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            failPostcondition(
                MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
                "A required addition source-context image is unreadable",
            )
        }
        if (FileHashing.sha256(actual) != expected.sha256) failPostcondition(
            MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
            "A non-target addition source-context image changed",
        )
    }
    val file = exactPostimageKtFile(targetPath)
    val declarations = if (provenFileBottomOffset == null) {
        file.declarations.map { declaration -> declaration as? KtNamedDeclaration ?: failPostcondition(
            MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
            "The added file contains an unsupported top-level declaration",
        ) }
    } else {
        exactFileBottomDeclaration(file, provenFileBottomOffset, relativeBaseOffset)
    }
    if (declarations.isEmpty()) failPostcondition(
        MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
        "The resulting addition declaration was not found at its authorized source range",
    )
    val parsed = ParsedAddition(
        file,
        declarations,
        file.packageFqName.toAdditionPackage(),
        AdditionAnalysisSource.PROJECT_POSTIMAGE,
    )
    val proofParts = try {
        proveAdditionDeclarations(parsed, relativeBaseOffset)
    } catch (failure: AdditionProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
            "The resulting addition declaration or outbound proof is incomplete: ${failure.message}",
        )
    }
    if (parsed.packageIdentity != expectedPackage || proofParts.declarations != expectedDeclarations) failPostcondition(
        MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
        "The resulting addition declaration identities changed",
    )
    if (proofParts.outbound != expectedOutbound) failPostcondition(
        MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
        "The resulting addition outbound occurrence set changed",
    )
    val exclusions = mapOf(
        targetPath to declarations.map { declaration ->
            declaration.textRange.startOffset until declaration.textRange.endOffset
        },
    )
    try {
        proveZeroRebindingCandidates(declarations, expectedPackage, currentOwner.sourceFiles, exclusions)
    } catch (failure: AdditionProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.COLLISION_OR_REBINDING_CHANGED,
            "The resulting addition has a new collision or rebinding candidate",
        )
    }
    return proofParts
}

internal fun exactFileBottomDeclaration(
    file: KtFile,
    provenFileBottomOffset: Int,
    relativeBaseOffset: Int,
): List<KtNamedDeclaration> {
    val appendedSyntax = file.children.filter { child ->
        child.textRange.endOffset > provenFileBottomOffset && child !is PsiWhiteSpace
    }
    val declaration = appendedSyntax.singleOrNull() as? KtNamedDeclaration
        ?: failPostcondition(
            MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
            "The FILE_BOTTOM append must contain exactly one supported top-level declaration",
        )
    if (declaration.textRange.startOffset != relativeBaseOffset) failPostcondition(
        MutationPostconditionLimitation.DECLARATION_SET_MISMATCH,
        "The FILE_BOTTOM declaration does not start at the exact authorized offset",
    )
    return listOf(declaration)
}

private fun KastIndexerBackend.currentKtFile(filePath: String): KtFile {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(filePath)) ?: failPostcondition(
        MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        "The mutation target is absent from the IntelliJ file system",
    )
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: failPostcondition(
        MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        "The mutation target has no current Kotlin semantic file",
    )
}

private fun KastIndexerBackend.exactPostimageKtFile(filePath: String): KtFile {
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

private fun TextEdit.rangeKey(): Triple<String, Int, Int> = Triple(filePath, startOffset, endOffset)

private fun Location.rangeKey(): Triple<String, Int, Int> = Triple(filePath, startOffset, endOffset)

private fun failPostcondition(
    limitation: MutationPostconditionLimitation,
    message: String,
): Nothing = throw MutationPostconditionFailedException.of(limitation, message = message)
