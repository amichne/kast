@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.semantic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.query.SemanticGraphPageToken
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.result.SemanticGraphCoverage
import io.github.amichne.kast.api.contract.result.SemanticGraphDiagnosticEvidence
import io.github.amichne.kast.api.contract.result.SemanticGraphFileCoverage
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphGeneration
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationContext
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.diagnostics.analyzeDiagnosticsFileInReadEpoch
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSnapshot
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class SemanticGraphQueryIdentity(
    val filePaths: List<SemanticGraphPath>,
    val removedFilePaths: List<SemanticGraphPath>,
    val pageSize: io.github.amichne.kast.api.contract.PositiveInt,
)

internal data class SemanticGraphSnapshot(
    val generation: SemanticGraphGeneration,
    val scopeFingerprint: SemanticGraphSha256,
    val coverage: SemanticGraphCoverage,
    val symbols: List<SemanticGraphSymbol>,
    val boundarySymbols: List<SemanticGraphSymbol>,
    val relations: List<SemanticGraphRelation>,
)

internal class SemanticGraphContinuationState(
    val snapshot: SemanticGraphSnapshot,
    nextOffset: Int,
) : ContinuationOwnedState() {
    var nextOffset: Int = nextOffset
        private set

    fun advanceTo(offset: Int) {
        require(offset > nextOffset) { "Semantic graph continuation offset must advance" }
        nextOffset = offset
    }
}

internal data class SemanticGraphContinuationProjection(
    val snapshot: SemanticGraphSnapshot,
    val pageOffset: Int,
) : ContinuationProjection()

private sealed interface SemanticGraphRecord {
    data class Symbol(val value: SemanticGraphSymbol) : SemanticGraphRecord
    data class BoundarySymbol(val value: SemanticGraphSymbol) : SemanticGraphRecord
    data class Relation(val value: SemanticGraphRelation) : SemanticGraphRecord
}

private data class ExtractedSemanticGraphFile(
    val update: SemanticGraphFileIndexUpdate,
    val boundarySymbols: List<SemanticGraphSymbol>,
    val omittedExternalTargetCount: Int,
)

private data class ResolvedSemanticTarget(
    val key: SemanticGraphSymbolKey,
    val boundarySymbol: SemanticGraphSymbol?,
)

private data class ResolvedSemanticCallTarget(
    val element: PsiElement?,
    val exactConstructorSignature: String?,
)

internal suspend fun KastPluginBackend.semanticGraphOperation(query: ParsedSemanticGraphQuery): SemanticGraphResult =
    withContext(readDispatcher) {
        val store = semanticGraphStore ?: throw CapabilityNotSupportedException(
            capability = "SEMANTIC_GRAPH",
            message = "Semantic graph extraction requires the IDEA source index",
        )
        store.ensureSchema()
        val identity = SemanticGraphQueryIdentity(query.filePaths, query.removedFilePaths, query.pageSize)
        val continuation = query.continuation
        if (continuation != null) {
            val consumed = semanticGraphContinuations.consume(
                continuation,
                identity,
                ContinuationStateTransition { state ->
                    if (store.readGeneration().value != state.snapshot.generation.value) {
                        throw ConflictException("Semantic graph generation changed after the preceding page")
                    }
                    val projection = SemanticGraphContinuationProjection(state.snapshot, state.nextOffset)
                    val nextOffset = semanticGraphNextOffset(
                        state.snapshot,
                        state.nextOffset,
                        identity.pageSize.value,
                    )
                    if (semanticGraphRecordCount(state.snapshot) > nextOffset) {
                        state.advanceTo(nextOffset)
                        ContinuationTransition.Reissue(projection, identity)
                    } else {
                        ContinuationTransition.Complete(projection)
                    }
                },
            )
            return@withContext when (consumed) {
                is ContinuationConsumeResult.Completed ->
                    semanticGraphPage(consumed.output, identity.pageSize.value, null)
                is ContinuationConsumeResult.Reissued ->
                    semanticGraphPage(consumed.output, identity.pageSize.value, consumed.token)
                is ContinuationConsumeResult.Rejected -> throw ConflictException(
                    "The semantic graph page token is unknown, expired, consumed, or belongs to another query",
                )
            }
        }

        val snapshot = ApplicationManager.getApplication().runReadAction<SemanticGraphSnapshot> {
            buildSemanticGraphSnapshot(query)
        }
        val nextOffset = semanticGraphNextOffset(snapshot, 0, identity.pageSize.value)
        val nextToken = if (semanticGraphRecordCount(snapshot) > nextOffset) {
            when (val issued = semanticGraphContinuations.issue(
                identity,
                SemanticGraphContinuationState(snapshot, nextOffset),
            )) {
                is ContinuationIssueResult.Issued -> issued.token
                is ContinuationIssueResult.Rejected -> throw ConflictException(
                    "Semantic graph continuation store is unavailable",
                )
            }
        } else {
            null
        }
        semanticGraphPage(SemanticGraphContinuationProjection(snapshot, 0), identity.pageSize.value, nextToken)
    }

private fun KastPluginBackend.buildSemanticGraphSnapshot(query: ParsedSemanticGraphQuery): SemanticGraphSnapshot {
    val store = requireNotNull(semanticGraphStore)
    val selectedPaths = query.filePaths.map(::toRelativeSemanticGraphPath)
    val removedPaths = query.removedFilePaths.map(::toRelativeSemanticGraphPath)
    val updates = mutableListOf<SemanticGraphFileIndexUpdate>()
    val coverage = mutableListOf<SemanticGraphFileCoverage>()
    val extractedBoundarySymbols = mutableMapOf<SemanticGraphSymbolKey, SemanticGraphSymbol>()
    var omittedExternalTargetCount = 0

    query.filePaths.zip(selectedPaths).forEach { (absolutePath, relativePath) ->
        val file = findKtFile(absolutePath.value.value)
        val contentHash = sha256(file.text)
        val diagnostics = analyzeDiagnosticsFileInReadEpoch(absolutePath.value)
        if (diagnostics.status.state.name != "ANALYZED") {
            throw ValidationException(
                "Kotlin diagnostics prevent semantic graph extraction for ${absolutePath.value.value}",
            )
        }
        val evidence = diagnostics.diagnostics.map { diagnostic ->
            SemanticGraphDiagnosticEvidence(
                severity = diagnostic.severity,
                message = NonBlankString(diagnostic.message),
                startOffset = ByteOffset(diagnostic.location.startOffset),
                endOffset = ByteOffset(diagnostic.location.endOffset),
                line = LineNumber(diagnostic.location.startLine.coerceAtLeast(1)),
            )
        }
        val extracted = extractSemanticGraphFile(file, relativePath, contentHash, evidence)
        updates += extracted.update
        extracted.boundarySymbols.forEach { symbol ->
            extractedBoundarySymbols[symbol.canonicalKey] = symbol
        }
        coverage += SemanticGraphFileCoverage(
            path = relativePath,
            contentHash = contentHash,
            status = SemanticGraphFileStatus.REFRESHED,
            diagnostics = evidence,
        )
        omittedExternalTargetCount = Math.addExact(
            omittedExternalTargetCount,
            extracted.omittedExternalTargetCount,
        )
    }

    if (updates.isNotEmpty() || removedPaths.isNotEmpty()) {
        store.replaceSemanticGraphFiles(updates, removedPaths)
    }
    coverage += removedPaths.map { path ->
        SemanticGraphFileCoverage(path, null, SemanticGraphFileStatus.REMOVED)
    }
    val persisted = store.readSemanticGraph(selectedPaths)
    val selectedSymbolKeys = persisted.symbols.mapTo(mutableSetOf(), SemanticGraphSymbol::canonicalKey)
    val boundarySymbols = extractedBoundarySymbols.values
        .filterNot { symbol -> symbol.canonicalKey in selectedSymbolKeys }
        .sortedBy(SemanticGraphSymbol::canonicalKey)
    val availableTargetKeys = selectedSymbolKeys + boundarySymbols.map(SemanticGraphSymbol::canonicalKey)
    val missingTargets = persisted.relations
        .map(SemanticGraphRelation::targetKey)
        .filterNot(availableTargetKeys::contains)
        .distinct()
    if (missingTargets.isNotEmpty()) {
        error(
            "Semantic graph extraction produced relations without selected or boundary targets: " +
                missingTargets.joinToString { it.value },
        )
    }
    return SemanticGraphSnapshot(
        generation = SemanticGraphGeneration(persisted.generation.value),
        scopeFingerprint = semanticGraphScopeFingerprint(selectedPaths, removedPaths),
        coverage = SemanticGraphCoverage(
            files = coverage.sortedBy(SemanticGraphFileCoverage::path),
            omittedExternalTargetCount = NonNegativeInt(omittedExternalTargetCount),
        ),
        symbols = persisted.symbols.sortedBy(SemanticGraphSymbol::canonicalKey),
        boundarySymbols = boundarySymbols,
        relations = persisted.relations.sortedWith(semanticGraphRelationOrder),
    )
}

private fun KastPluginBackend.extractSemanticGraphFile(
    file: KtFile,
    path: SemanticGraphSourcePath,
    contentHash: SemanticGraphSha256,
    diagnostics: List<SemanticGraphDiagnosticEvidence>,
): ExtractedSemanticGraphFile {
    val declarations = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration -> projectableKind(declaration) != null }
        .sortedBy { declaration -> declaration.textRange.startOffset }
    val symbolByDeclaration = declarations.associateWith { declaration -> semanticGraphSymbol(declaration, path) }
    val fileSymbol = SemanticGraphSymbol(
        canonicalKey = fileKey(path),
        kind = SemanticGraphSymbolKind.FILE,
        name = NonBlankString(file.name),
        path = path,
        startOffset = ByteOffset(0),
        endOffset = ByteOffset(file.textLength),
        line = LineNumber(1),
    )
    val symbols = listOf(fileSymbol) + symbolByDeclaration.values
    val boundarySymbols = mutableMapOf<SemanticGraphSymbolKey, SemanticGraphSymbol>()
    val relations = mutableListOf<SemanticGraphRelation>()
    declarations.forEach { declaration ->
        val symbol = symbolByDeclaration.getValue(declaration)
        val owner = nearestProjectedOwner(declaration, symbolByDeclaration) ?: fileSymbol
        val relationKind = when {
            declaration is KtEnumEntry -> SemanticGraphRelationKind.CASE_OF
            declaration is KtNamedFunction && owner.kind in setOf(
                SemanticGraphSymbolKind.CLASS,
                SemanticGraphSymbolKind.INTERFACE,
                SemanticGraphSymbolKind.OBJECT,
                SemanticGraphSymbolKind.ENUM_CLASS,
            ) -> SemanticGraphRelationKind.METHOD
            else -> SemanticGraphRelationKind.CONTAINS
        }
        relations += relation(owner, symbol.canonicalKey, relationKind, SemanticGraphRelationContext.NONE, declaration, path)
    }

    var omittedExternalTargetCount = 0
    PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
        .sortedBy { it.textRange.startOffset }
        .forEach { call ->
            val target = analyze(call) {
                val symbol = call.resolveToCall()
                    ?.singleFunctionCallOrNull()
                    ?.partiallyAppliedSymbol
                    ?.signature
                    ?.symbol
                ResolvedSemanticCallTarget(
                    element = symbol?.psi,
                    exactConstructorSignature = (symbol as? KaConstructorSymbol)?.compilerStableSignature(),
                )
            }
            val source = nearestProjectedOwner(call, symbolByDeclaration) ?: fileSymbol
            val semanticTarget = target.element?.let { semanticTarget(it, path) }
            if (semanticTarget != null) {
                semanticTarget.boundarySymbol?.let { symbol ->
                    boundarySymbols[symbol.canonicalKey] = symbol
                }
                val resolvedTargetKey = target.exactConstructorSignature?.let { signature ->
                    val targetPath = relativePathOr(requireNotNull(target.element), path)
                    SemanticGraphSymbolKey.parse("constructor:${targetPath.value}:$signature")
                }
                relations += relation(
                    source,
                    semanticTarget.key,
                    SemanticGraphRelationKind.CALLS,
                    SemanticGraphRelationContext.CALL,
                    call,
                    path,
                    resolvedTargetKey,
                )
            } else if (
                target.element == null ||
                target.element.containingFile !is KtFile ||
                !isWorkspaceFile(target.element.containingFile.virtualFile.path)
            ) {
                omittedExternalTargetCount++
            }
        }

    PsiTreeUtil.findChildrenOfType(file, KtSuperTypeListEntry::class.java)
        .sortedBy { it.textRange.startOffset }
        .forEach { entry ->
            val source = nearestProjectedOwner(entry, symbolByDeclaration) ?: return@forEach
            val target = entry.typeReference?.resolveTypeTarget()
            val semanticTarget = target?.let { semanticTarget(it, path) }
            if (semanticTarget != null) {
                semanticTarget.boundarySymbol?.let { symbol ->
                    boundarySymbols[symbol.canonicalKey] = symbol
                }
                val kind = if ((target as? KtClass)?.isInterface() == true) {
                    SemanticGraphRelationKind.IMPLEMENTS
                } else {
                    SemanticGraphRelationKind.INHERITS
                }
                relations += relation(source, semanticTarget.key, kind, SemanticGraphRelationContext.NONE, entry, path)
            } else if (target == null || target.containingFile !is KtFile || !isWorkspaceFile(target.containingFile.virtualFile.path)) {
                omittedExternalTargetCount++
            }
        }

    PsiTreeUtil.findChildrenOfType(file, KtTypeReference::class.java)
        .sortedBy { it.textRange.startOffset }
        .forEach { reference ->
            val baseContext = reference.referenceContext() ?: return@forEach
            PsiTreeUtil.findChildrenOfType(reference, KtUserType::class.java)
                .sortedBy { it.textRange.startOffset }
                .forEach { userType ->
                    val target = userType.resolveTarget()
                    val semanticTarget = target?.let { semanticTarget(it, path) }
                    val source = nearestProjectedOwner(reference, symbolByDeclaration) ?: fileSymbol
                    if (semanticTarget != null) {
                        semanticTarget.boundarySymbol?.let { symbol ->
                            boundarySymbols[symbol.canonicalKey] = symbol
                        }
                        val context = if (PsiTreeUtil.getParentOfType(userType, KtTypeProjection::class.java, false) != null) {
                            SemanticGraphRelationContext.GENERIC_ARG
                        } else {
                            baseContext
                        }
                        relations += relation(
                            source,
                            semanticTarget.key,
                            SemanticGraphRelationKind.REFERENCES,
                            context,
                            userType,
                            path,
                        )
                    } else if (target == null || target.containingFile !is KtFile || !isWorkspaceFile(target.containingFile.virtualFile.path)) {
                        omittedExternalTargetCount++
                    }
                }
        }

    return ExtractedSemanticGraphFile(
        update = SemanticGraphFileIndexUpdate(
            path = path,
            contentHash = contentHash,
            status = SemanticGraphFileStatus.REFRESHED,
            diagnostics = diagnostics,
            symbols = symbols,
            relations = relations.distinct().sortedWith(semanticGraphRelationOrder),
        ),
        boundarySymbols = boundarySymbols.values.sortedBy(SemanticGraphSymbol::canonicalKey),
        omittedExternalTargetCount = omittedExternalTargetCount,
    )
}

private fun KastPluginBackend.semanticGraphSymbol(
    declaration: KtNamedDeclaration,
    path: SemanticGraphSourcePath,
): SemanticGraphSymbol {
    val kind = requireNotNull(projectableKind(declaration))
    val canonicalKey = declaration.semanticKey(path)
    val callableSignature = (declaration as? KtNamedFunction)?.compilerStableSignature()
    val owner = nearestProjectedDeclaration(declaration.parent)?.semanticKey(path)
    return SemanticGraphSymbol(
        canonicalKey = canonicalKey,
        kind = kind,
        name = NonBlankString(declaration.name ?: declaration.text),
        fqName = declaration.fqName?.asString()?.let(::FqName),
        signature = callableSignature?.let(::NonBlankString),
        ownerKey = owner,
        path = path,
        startOffset = ByteOffset(declaration.textRange.startOffset),
        endOffset = ByteOffset(declaration.textRange.endOffset),
        line = LineNumber(declaration.line()),
    )
}

private fun KtNamedDeclaration.semanticKey(path: SemanticGraphSourcePath): SemanticGraphSymbolKey = when (this) {
    is KtEnumEntry -> {
        val parent = PsiTreeUtil.getParentOfType(this, KtClassOrObject::class.java, true)
        SemanticGraphSymbolKey.parse("enum-entry:${parent?.semanticKey(path)?.value ?: fileKey(path).value}:${name}")
    }
    is KtClassOrObject -> {
        val classId = analyze(this) { (symbol as? KaClassSymbol)?.classId?.asSingleFqName()?.asString() }
        classId?.let {
            SemanticGraphSymbolKey.parse("class:${projectableKind(this)}:${path.value}:${textRange.startOffset}:$it")
        }
            ?: localKey(path, this, requireNotNull(projectableKind(this)))
    }
    is KtNamedFunction -> compilerStableSignature()
        ?.let { SemanticGraphSymbolKey.parse("callable:${path.value}:${textRange.startOffset}:$it") }
        ?: localKey(path, this, requireNotNull(projectableKind(this)))
    else -> localKey(path, this, requireNotNull(projectableKind(this)))
}

private fun KastPluginBackend.semanticTarget(
    target: PsiElement,
    sourcePath: SemanticGraphSourcePath,
): ResolvedSemanticTarget? {
    val targetFile = target.containingFile as? KtFile ?: return null
    if (!isWorkspaceFile(targetFile.virtualFile.path)) return null
    val declaration = when (target) {
        is KtNamedFunction -> target
        is KtClassOrObject -> target
        else -> PsiTreeUtil.getParentOfType(target, KtClassOrObject::class.java, false)
    } ?: return null
    val targetPath = relativePathOr(declaration, sourcePath)
    val symbol = semanticGraphSymbol(declaration, targetPath)
    return ResolvedSemanticTarget(
        key = symbol.canonicalKey,
        boundarySymbol = symbol.takeUnless { targetPath == sourcePath },
    )
}

private fun KastPluginBackend.relativePathOr(
    element: PsiElement,
    fallback: SemanticGraphSourcePath,
): SemanticGraphSourcePath =
    element.containingFile?.virtualFile?.path?.let { absolute ->
        runCatching { workspaceRoot.relativize(java.nio.file.Path.of(absolute).toAbsolutePath().normalize()) }
            .getOrNull()
            ?.takeUnless { relative -> relative.startsWith("..") }
            ?.toString()
            ?.let(SemanticGraphSourcePath::parse)
    } ?: fallback

private fun KtTypeReference.resolveTypeTarget(): PsiElement? =
    PsiTreeUtil.findChildOfType(this, KtUserType::class.java)?.resolveTarget()

private fun KtUserType.resolveTarget(): PsiElement? = analyze(this) {
    referenceExpression?.references
        ?.filterIsInstance<KtReference>()
        ?.firstOrNull()
        ?.resolveToSymbol()
        ?.psi
}

private fun KtNamedFunction.compilerStableSignature(): String? = analyze(this) { symbol.compilerStableSignature() }

private fun KaFunctionSymbol.compilerStableSignature(): String? {
    val callableIdentity = when (this) {
        is KaConstructorSymbol -> "${containingClassId?.asSingleFqName()?.asString() ?: return null}.<init>"
        else -> callableId?.asSingleFqName()?.asString() ?: return null
    }
    return buildString {
        append(callableIdentity).append('|')
        append(receiverParameter?.returnType?.toString()?.canonicalTypeText() ?: "-").append('|')
        append(
            contextReceivers.joinToString(",") { receiver -> receiver.type.toString().canonicalTypeText() },
        ).append('|')
        append(
            valueParameters.joinToString(",") { parameter -> parameter.returnType.toString().canonicalTypeText() },
        ).append('|')
        append((this@compilerStableSignature as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0)
    }
}

private fun String.canonicalTypeText(): String = replace('/', '.')

private fun KtTypeReference.referenceContext(): SemanticGraphRelationContext? = when (parent) {
    is KtProperty -> SemanticGraphRelationContext.FIELD
    is KtParameter -> SemanticGraphRelationContext.PARAMETER_TYPE
    is KtNamedFunction -> SemanticGraphRelationContext.RETURN_TYPE
    else -> null
}

private fun projectableKind(declaration: KtNamedDeclaration): SemanticGraphSymbolKind? = when (declaration) {
    is KtEnumEntry -> SemanticGraphSymbolKind.ENUM_ENTRY
    is KtObjectDeclaration -> SemanticGraphSymbolKind.OBJECT
    is KtClass -> when {
        declaration.isInterface() -> SemanticGraphSymbolKind.INTERFACE
        declaration.isEnum() -> SemanticGraphSymbolKind.ENUM_CLASS
        else -> SemanticGraphSymbolKind.CLASS
    }
    is KtNamedFunction -> if (PsiTreeUtil.getParentOfType(declaration, KtClassOrObject::class.java, true) != null) {
        SemanticGraphSymbolKind.MEMBER_FUNCTION
    } else {
        SemanticGraphSymbolKind.FUNCTION
    }
    else -> null
}

private fun nearestProjectedDeclaration(element: PsiElement?): KtNamedDeclaration? =
    generateSequence(element) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .firstOrNull { declaration -> projectableKind(declaration) != null }

private fun nearestProjectedOwner(
    element: PsiElement,
    symbols: Map<KtNamedDeclaration, SemanticGraphSymbol>,
): SemanticGraphSymbol? = generateSequence(element.parent) { it.parent }
    .filterIsInstance<KtNamedDeclaration>()
    .firstNotNullOfOrNull(symbols::get)

private fun relation(
    source: SemanticGraphSymbol,
    targetKey: SemanticGraphSymbolKey,
    kind: SemanticGraphRelationKind,
    context: SemanticGraphRelationContext,
    evidence: PsiElement,
    path: SemanticGraphSourcePath,
    resolvedTargetKey: SemanticGraphSymbolKey? = null,
): SemanticGraphRelation = SemanticGraphRelation(
    sourceKey = source.canonicalKey,
    targetKey = targetKey,
    resolvedTargetKey = resolvedTargetKey,
    kind = kind,
    context = context,
    sourcePath = path,
    startOffset = ByteOffset(evidence.textRange.startOffset),
    endOffset = ByteOffset(evidence.textRange.endOffset),
    line = LineNumber(evidence.line()),
)

private fun PsiElement.line(): Int = containingFile.text.substring(0, textRange.startOffset).count { it == '\n' } + 1

private fun fileKey(path: SemanticGraphSourcePath): SemanticGraphSymbolKey =
    SemanticGraphSymbolKey.parse("file:${path.value}")

private fun localKey(
    path: SemanticGraphSourcePath,
    declaration: KtNamedDeclaration,
    kind: SemanticGraphSymbolKind,
): SemanticGraphSymbolKey = SemanticGraphSymbolKey.parse(
    "local:${path.value}:${declaration.textRange.startOffset}:${kind.name}",
)

private fun KastPluginBackend.toRelativeSemanticGraphPath(path: SemanticGraphPath): SemanticGraphSourcePath {
    val absolute = path.value.toJavaPath()
    require(absolute.startsWith(workspaceRoot)) {
        "Semantic graph path is outside the active workspace: ${path.value.value}"
    }
    return SemanticGraphSourcePath.parse(workspaceRoot.relativize(absolute).toString())
}

private fun sha256(value: String): SemanticGraphSha256 = SemanticGraphSha256.parse(
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) },
)

private fun semanticGraphScopeFingerprint(
    selectedPaths: List<SemanticGraphSourcePath>,
    removedPaths: List<SemanticGraphSourcePath>,
): SemanticGraphSha256 = sha256(
    buildString {
        selectedPaths.sorted().forEach { append("selected:").append(it.value).append('\n') }
        removedPaths.sorted().forEach { append("removed:").append(it.value).append('\n') }
    },
)

private val semanticGraphRelationOrder = compareBy<SemanticGraphRelation>(
    SemanticGraphRelation::sourceKey,
    SemanticGraphRelation::targetKey,
    { it.kind.name },
    { it.context.name },
    SemanticGraphRelation::sourcePath,
    SemanticGraphRelation::startOffset,
)

private fun semanticGraphRecordCount(snapshot: SemanticGraphSnapshot): Int =
    Math.addExact(
        Math.addExact(snapshot.symbols.size, snapshot.boundarySymbols.size),
        snapshot.relations.size,
    )

private fun semanticGraphNextOffset(
    snapshot: SemanticGraphSnapshot,
    offset: Int,
    pageSize: Int,
): Int {
    val recordCount = semanticGraphRecordCount(snapshot)
    if (offset !in 0..recordCount) {
        throw ConflictException("Server-held semantic graph continuation offset exceeded exact cardinality")
    }
    return Math.addExact(offset, minOf(pageSize, recordCount - offset))
}

private fun semanticGraphPage(
    projection: SemanticGraphContinuationProjection,
    pageSize: Int,
    nextToken: SemanticGraphPageToken?,
): SemanticGraphResult {
    val snapshot = projection.snapshot
    val records = snapshot.symbols.map(SemanticGraphRecord::Symbol) +
        snapshot.boundarySymbols.map(SemanticGraphRecord::BoundarySymbol) +
        snapshot.relations.map(SemanticGraphRecord::Relation)
    val page = records.drop(projection.pageOffset).take(pageSize)
    return SemanticGraphResult(
        generation = snapshot.generation,
        scopeFingerprint = snapshot.scopeFingerprint,
        coverage = snapshot.coverage,
        symbols = page.filterIsInstance<SemanticGraphRecord.Symbol>().map(SemanticGraphRecord.Symbol::value),
        boundarySymbols = page.filterIsInstance<SemanticGraphRecord.BoundarySymbol>()
            .map(SemanticGraphRecord.BoundarySymbol::value),
        relations = page.filterIsInstance<SemanticGraphRecord.Relation>().map(SemanticGraphRecord.Relation::value),
        nextPageToken = nextToken,
    )
}
