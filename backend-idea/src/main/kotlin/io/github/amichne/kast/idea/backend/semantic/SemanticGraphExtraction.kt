@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.semantic

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.result.SemanticGraphDiagnosticEvidence
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationContext
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphOrigin
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

internal data class ExtractedSemanticGraphFile(
    val update: SemanticGraphFileIndexUpdate,
    val boundarySymbols: List<SemanticGraphSymbol>,
    val omittedExternalTargetCount: Int,
)

private data class ResolvedSemanticCallTarget(
    val element: PsiElement?,
    val exactConstructorSignature: String?,
)

internal fun KastPluginBackend.extractSemanticGraphFile(
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
            val signature = constructor.compilerStableSignature() ?: constructor.valueParameters.joinToString(
                prefix = "(",
                postfix = ")",
            ) { parameter -> parameter.typeReference?.text?.canonicalTypeText() ?: "?" }
            val symbol = syntheticSemanticGraphSymbol(
                element = constructor,
                path = path,
                key = semanticConstructorKey(owner.canonicalKey, constructor, signature),
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
                    val constructor = target.element as? KtConstructor<*> ?: return@let null
                    val owner = PsiTreeUtil.getParentOfType(constructor, KtClassOrObject::class.java, true)
                        ?: return@let null
                    val targetPath = relativePathOr(constructor, path)
                    semanticConstructorKey(owner.semanticKey(targetPath), constructor, signature)
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


private val semanticGraphRelationOrder = compareBy<SemanticGraphRelation>(
    SemanticGraphRelation::sourceKey,
    SemanticGraphRelation::targetKey,
    { it.kind.name },
    { it.context.name },
    SemanticGraphRelation::sourcePath,
    SemanticGraphRelation::startOffset,
)
