@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.semantic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
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
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolFlags
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphModality
import io.github.amichne.kast.api.contract.result.SemanticGraphOrigin
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeEdge
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeFact
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeKind
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeNullability
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeRole
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeVariance
import io.github.amichne.kast.api.contract.result.SemanticGraphVisibility
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.diagnostics.analyzeDiagnosticsFileInReadEpoch
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.directlyOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.components.getExpectsForActual
import org.jetbrains.kotlin.analysis.api.components.sealedClassInheritors
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeConstraint
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
        ApplicationManager.getApplication().runReadAction<SemanticGraphResult> {
            buildSemanticGraphSnapshot(query)
        }
    }

private fun KastPluginBackend.buildSemanticGraphSnapshot(query: ParsedSemanticGraphQuery): SemanticGraphResult {
    val store = requireNotNull(semanticGraphStore)
    val selectedPaths = query.filePaths.map(::toRelativeSemanticGraphPath)
    val removedPaths = query.removedFilePaths.map(::toRelativeSemanticGraphPath)
    val updates = mutableListOf<SemanticGraphFileIndexUpdate>()
    val coverage = mutableListOf<SemanticGraphFileCoverage>()
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

    val writeResult = if (updates.isNotEmpty() || removedPaths.isNotEmpty()) {
        store.replaceSemanticGraphFiles(updates, removedPaths)
    } else {
        null
    }
    coverage += removedPaths.map { path ->
        SemanticGraphFileCoverage(path, null, SemanticGraphFileStatus.REMOVED)
    }
    return SemanticGraphResult(
        generation = SemanticGraphGeneration(writeResult?.generation?.value ?: store.readGeneration().value),
        scopeFingerprint = semanticGraphScopeFingerprint(selectedPaths, removedPaths),
        coverage = SemanticGraphCoverage(
            files = coverage.sortedBy(SemanticGraphFileCoverage::path),
            omittedExternalTargetCount = NonNegativeInt(omittedExternalTargetCount),
        ),
        symbolCount = NonNegativeInt(writeResult?.symbolCount ?: 0),
        edgeOccurrenceCount = NonNegativeInt(writeResult?.edgeOccurrenceCount ?: 0),
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
    val syntheticSymbols = mutableListOf<SemanticGraphSymbol>()
    val syntheticRelations = mutableListOf<SemanticGraphRelation>()
    PsiTreeUtil.findChildrenOfType(file, KtConstructor::class.java)
        .sortedBy { constructor -> constructor.textRange.startOffset }
        .forEach { constructor ->
            val ownerDeclaration = PsiTreeUtil.getParentOfType(constructor, KtClassOrObject::class.java, true)
                ?: return@forEach
            val owner = symbolByDeclaration[ownerDeclaration] ?: return@forEach
            val signature = constructor.valueParameters.joinToString(
                prefix = "(",
                postfix = ")",
            ) { parameter -> parameter.typeReference?.text?.canonicalTypeText() ?: "?" }
            val symbol = syntheticSemanticGraphSymbol(
                element = constructor,
                path = path,
                key = SemanticGraphSymbolKey.parse(
                    "constructor:${owner.canonicalKey.value}:${constructor.textRange.startOffset}:$signature",
                ),
                kind = SemanticGraphSymbolKind.CONSTRUCTOR,
                name = "<init>",
                owner = owner,
                signature = signature,
            )
            syntheticSymbols += symbol
            syntheticRelations += relation(
                owner,
                symbol.canonicalKey,
                SemanticGraphRelationKind.METHOD,
                SemanticGraphRelationContext.NONE,
                constructor,
                path,
            )
        }
    symbolByDeclaration.forEach { (declaration, owner) ->
        val callable = declaration as? org.jetbrains.kotlin.psi.KtCallableDeclaration ?: return@forEach
        callable.receiverTypeReference?.let { receiver ->
            val symbol = syntheticSemanticGraphSymbol(
                element = receiver,
                path = path,
                key = SemanticGraphSymbolKey.parse("receiver:${owner.canonicalKey.value}"),
                kind = SemanticGraphSymbolKind.RECEIVER_PARAMETER,
                name = "<receiver>",
                owner = owner,
                declaredTypeKey = semanticTypeKey(receiver),
            )
            syntheticSymbols += symbol
            syntheticRelations += relation(
                owner,
                symbol.canonicalKey,
                SemanticGraphRelationKind.CONTAINS,
                SemanticGraphRelationContext.RECEIVER_TYPE,
                receiver,
                path,
            )
        }
        val property = declaration as? KtProperty ?: return@forEach
        listOfNotNull(
            property.getter to SemanticGraphSymbolKind.GETTER,
            (property.setter to SemanticGraphSymbolKind.SETTER).takeIf { property.isVar },
        ).forEach { (accessor, kind) ->
            val evidence = accessor ?: property
            val symbol = syntheticSemanticGraphSymbol(
                element = evidence,
                path = path,
                key = SemanticGraphSymbolKey.parse(
                    "accessor:${owner.canonicalKey.value}:${if (kind == SemanticGraphSymbolKind.GETTER) "get" else "set"}",
                ),
                kind = kind,
                name = if (kind == SemanticGraphSymbolKind.GETTER) "<get>" else "<set>",
                owner = owner,
                origin = if (accessor == null) SemanticGraphOrigin.SYNTHETIC else SemanticGraphOrigin.SOURCE,
                returnTypeKey = owner.declaredTypeKey,
            )
            syntheticSymbols += symbol
            syntheticRelations += relation(
                owner,
                symbol.canonicalKey,
                SemanticGraphRelationKind.CONTAINS,
                SemanticGraphRelationContext.NONE,
                evidence,
                path,
            )
        }
    }
    val symbols = listOf(fileSymbol) + symbolByDeclaration.values + syntheticSymbols
    val boundarySymbols = mutableMapOf<SemanticGraphSymbolKey, SemanticGraphSymbol>()
    val relations = syntheticRelations.toMutableList()
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
    declarations.forEach { declaration ->
        val source = symbolByDeclaration.getValue(declaration)
        declaration.semanticCompilerRelations().forEach { (target, kind) ->
            val semanticTarget = semanticTarget(target, path)
            if (semanticTarget != null) {
                semanticTarget.boundarySymbol?.let { symbol ->
                    boundarySymbols[symbol.canonicalKey] = symbol
                }
                relations += relation(
                    source,
                    semanticTarget.key,
                    kind,
                    SemanticGraphRelationContext.NONE,
                    declaration,
                    path,
                )
            }
        }
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
                val kind = when {
                    entry is KtDelegatedSuperTypeEntry -> SemanticGraphRelationKind.DELEGATES
                    (target as? KtClass)?.isInterface() == true -> SemanticGraphRelationKind.IMPLEMENTS
                    else -> SemanticGraphRelationKind.INHERITS
                }
                relations += relation(
                    source,
                    semanticTarget.key,
                    kind,
                    if (entry is KtDelegatedSuperTypeEntry) {
                        SemanticGraphRelationContext.DELEGATE
                    } else {
                        SemanticGraphRelationContext.NONE
                    },
                    entry,
                    path,
                )
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
            packageName = file.packageFqName.asString().takeIf(String::isNotBlank),
            moduleName = ProjectFileIndex.getInstance(project).getModuleForFile(file.virtualFile)?.name,
            contentHash = contentHash,
            status = SemanticGraphFileStatus.REFRESHED,
            diagnostics = diagnostics,
            types = semanticTypeFacts(file),
            symbols = symbols,
            boundarySymbols = boundarySymbols.values.sortedBy(SemanticGraphSymbol::canonicalKey),
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
    val declaredType = declaration.declaredSemanticTypeReference()
    val receiverType = (declaration as? org.jetbrains.kotlin.psi.KtCallableDeclaration)?.receiverTypeReference
    return SemanticGraphSymbol(
        canonicalKey = canonicalKey,
        kind = kind,
        name = NonBlankString(declaration.name ?: declaration.text),
        fqName = declaration.fqName?.asString()?.let(::FqName),
        signature = callableSignature?.let(::NonBlankString),
        ownerKey = owner,
        visibility = declaration.semanticVisibility(),
        modality = declaration.semanticModality(),
        flags = SemanticGraphSymbolFlags(
            isExpect = declaration.hasModifier(KtTokens.EXPECT_KEYWORD),
            isActual = declaration.hasModifier(KtTokens.ACTUAL_KEYWORD),
            isOverride = declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD),
            isSealed = declaration.hasModifier(KtTokens.SEALED_KEYWORD),
            isDelegated = (declaration as? KtProperty)?.delegate != null,
        ),
        annotations = declaration.annotationEntries.mapNotNull { annotation ->
            val resolved = annotation.typeReference
                ?.resolveTypeTarget()
                ?.let { target -> target as? KtNamedDeclaration }
                ?.fqName
                ?.asString()
            (resolved ?: annotation.shortName?.asString())?.let(::NonBlankString)
        }.distinct().sortedBy(NonBlankString::value),
        declaredTypeKey = declaredType?.let(::semanticTypeKey),
        receiverTypeKey = receiverType?.let(::semanticTypeKey),
        returnTypeKey = (declaration as? KtNamedFunction)?.typeReference?.let(::semanticTypeKey),
        path = path,
        startOffset = ByteOffset(declaration.textRange.startOffset),
        endOffset = ByteOffset(declaration.textRange.endOffset),
        line = LineNumber(declaration.line()),
    )
}

private fun syntheticSemanticGraphSymbol(
    element: PsiElement,
    path: SemanticGraphSourcePath,
    key: SemanticGraphSymbolKey,
    kind: SemanticGraphSymbolKind,
    name: String,
    owner: SemanticGraphSymbol,
    signature: String? = null,
    origin: SemanticGraphOrigin = SemanticGraphOrigin.SOURCE,
    declaredTypeKey: NonBlankString? = null,
    returnTypeKey: NonBlankString? = null,
): SemanticGraphSymbol = SemanticGraphSymbol(
    canonicalKey = key,
    kind = kind,
    name = NonBlankString(name),
    signature = signature?.let(::NonBlankString),
    ownerKey = owner.canonicalKey,
    visibility = owner.visibility,
    modality = owner.modality,
    origin = origin,
    declaredTypeKey = declaredTypeKey,
    returnTypeKey = returnTypeKey,
    path = path,
    startOffset = ByteOffset(element.textRange.startOffset),
    endOffset = ByteOffset(element.textRange.endOffset),
    line = LineNumber(element.line()),
)

private fun KtNamedDeclaration.declaredSemanticTypeReference(): KtTypeReference? = when (this) {
    is KtNamedFunction -> typeReference
    is KtProperty -> typeReference
    is KtParameter -> typeReference
    is KtTypeAlias -> getTypeReference()
    else -> null
}

private fun KtNamedDeclaration.semanticVisibility(): SemanticGraphVisibility = when {
    hasModifier(KtTokens.PRIVATE_KEYWORD) -> SemanticGraphVisibility.PRIVATE
    hasModifier(KtTokens.PROTECTED_KEYWORD) -> SemanticGraphVisibility.PROTECTED
    hasModifier(KtTokens.INTERNAL_KEYWORD) -> SemanticGraphVisibility.INTERNAL
    fqName == null && this is KtNamedFunction -> SemanticGraphVisibility.LOCAL
    else -> SemanticGraphVisibility.PUBLIC
}

private fun KtNamedDeclaration.semanticModality(): SemanticGraphModality? = when {
    hasModifier(KtTokens.SEALED_KEYWORD) -> SemanticGraphModality.SEALED
    hasModifier(KtTokens.ABSTRACT_KEYWORD) -> SemanticGraphModality.ABSTRACT
    hasModifier(KtTokens.OPEN_KEYWORD) -> SemanticGraphModality.OPEN
    this is KtClassOrObject || this is KtNamedFunction || this is KtProperty -> SemanticGraphModality.FINAL
    else -> null
}

private fun semanticTypeFacts(file: KtFile): List<SemanticGraphTypeFact> =
    PsiTreeUtil.findChildrenOfType(file, KtTypeReference::class.java)
        .map(::semanticTypeFact)
        .distinctBy(SemanticGraphTypeFact::stableKey)
        .sortedBy { type -> type.stableKey.value }

private fun semanticTypeFact(reference: KtTypeReference): SemanticGraphTypeFact {
    val text = reference.text.canonicalTypeText()
    val functionType = reference.typeElement as? KtFunctionType
    val childReferences = buildList {
        functionType?.receiverTypeReference?.let { receiver ->
            add(Triple(receiver, SemanticGraphTypeRole.RECEIVER, SemanticGraphTypeVariance.INVARIANT))
        }
        functionType?.parameters?.forEach { parameter ->
            parameter.typeReference?.let { argument ->
                add(Triple(argument, SemanticGraphTypeRole.ARGUMENT, SemanticGraphTypeVariance.INVARIANT))
            }
        }
        functionType?.returnTypeReference?.let { returned ->
            add(Triple(returned, SemanticGraphTypeRole.RETURN, SemanticGraphTypeVariance.OUT))
        }
        PsiTreeUtil.findChildrenOfType(reference, KtTypeProjection::class.java)
            .filter { projection -> projection.parent?.parent == reference.typeElement }
            .forEach { projection ->
                projection.typeReference?.let { argument ->
                    add(
                        Triple(
                            argument,
                            SemanticGraphTypeRole.ARGUMENT,
                            when {
                                projection.text.trimStart().startsWith("in ") -> SemanticGraphTypeVariance.IN
                                projection.text.trimStart().startsWith("out ") -> SemanticGraphTypeVariance.OUT
                                else -> SemanticGraphTypeVariance.INVARIANT
                            },
                        ),
                    )
                }
            }
    }
    val classifier = runCatching {
        PsiTreeUtil.findChildOfType(reference, KtUserType::class.java)
            ?.resolveTarget()
            ?.let { target -> target as? KtNamedDeclaration }
            ?.fqName
            ?.asString()
    }.getOrNull() ?: PsiTreeUtil.findChildOfType(reference, KtUserType::class.java)?.referencedName
    return SemanticGraphTypeFact(
        stableKey = semanticTypeKey(reference),
        kind = when {
            text.contains("<ERROR", ignoreCase = true) -> SemanticGraphTypeKind.ERROR
            "suspend(" in text || text.startsWith("suspend") -> SemanticGraphTypeKind.SUSPEND_FUNCTION
            "->" in text -> SemanticGraphTypeKind.FUNCTION
            '&' in text -> SemanticGraphTypeKind.INTERSECTION
            text == "dynamic" -> SemanticGraphTypeKind.DYNAMIC
            classifier != null -> SemanticGraphTypeKind.CLASS
            else -> SemanticGraphTypeKind.UNKNOWN
        },
        classifier = classifier?.takeIf(String::isNotBlank)?.let(::NonBlankString),
        nullability = when {
            text.endsWith('?') -> SemanticGraphTypeNullability.NULLABLE
            text.endsWith('!') -> SemanticGraphTypeNullability.PLATFORM
            else -> SemanticGraphTypeNullability.NON_NULL
        },
        debugText = NonBlankString(text),
        edges = childReferences.mapIndexed { index, (child, role, variance) ->
            SemanticGraphTypeEdge(
                childKey = semanticTypeKey(child),
                role = role,
                position = NonNegativeInt(index),
                variance = variance,
            )
        },
    )
}

private fun semanticTypeKey(reference: KtTypeReference): NonBlankString =
    NonBlankString("type:${reference.text.canonicalTypeText()}")

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
    val declaration = when {
        target is KtNamedDeclaration && projectableKind(target) != null -> target
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

private fun KtNamedDeclaration.semanticCompilerRelations(): List<Pair<PsiElement, SemanticGraphRelationKind>> =
    analyze(this) {
        buildList {
            val declarationSymbol = symbol
            (declarationSymbol as? KaCallableSymbol)
                ?.directlyOverriddenSymbols
                ?.mapNotNull { overridden -> overridden.psi }
                ?.forEach { target -> add(target to SemanticGraphRelationKind.OVERRIDES) }
            declarationSymbol.getExpectsForActual()
                .mapNotNull { expected -> expected.psi }
                .forEach { target -> add(target to SemanticGraphRelationKind.EXPECT_ACTUAL) }
            (declarationSymbol as? KaNamedClassSymbol)
                ?.sealedClassInheritors
                ?.mapNotNull { inheritor -> inheritor.psi }
                ?.forEach { target -> add(target to SemanticGraphRelationKind.SEALED_MEMBER) }
        }
    }

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
    is KtAnnotationEntry -> SemanticGraphRelationContext.ANNOTATION
    is KtProperty -> SemanticGraphRelationContext.FIELD
    is KtParameter -> SemanticGraphRelationContext.PARAMETER_TYPE
    is KtNamedFunction -> SemanticGraphRelationContext.RETURN_TYPE
    is KtTypeConstraint -> SemanticGraphRelationContext.TYPE_CONSTRAINT
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
    is KtProperty -> SemanticGraphSymbolKind.PROPERTY
    is KtParameter -> SemanticGraphSymbolKind.VALUE_PARAMETER
    is KtTypeParameter -> SemanticGraphSymbolKind.TYPE_PARAMETER
    is KtTypeAlias -> SemanticGraphSymbolKind.TYPE_ALIAS
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
