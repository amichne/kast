@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationContext
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolFlags
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphModality
import io.github.amichne.kast.api.contract.result.SemanticGraphOrigin
import io.github.amichne.kast.api.contract.result.SemanticGraphVisibility
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.directlyOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.components.getExpectsForActual
import org.jetbrains.kotlin.analysis.api.components.sealedClassInheritors
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter

internal data class ResolvedSemanticTarget(
    val key: SemanticGraphSymbolKey,
    val boundarySymbol: SemanticGraphSymbol?,
)

internal fun KastPluginBackend.semanticGraphSymbol(
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

internal fun syntheticSemanticGraphSymbol(
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

private fun KtNamedDeclaration.semanticVisibility(): SemanticGraphVisibility = when {
    hasModifier(KtTokens.PRIVATE_KEYWORD) -> SemanticGraphVisibility.PRIVATE
    hasModifier(KtTokens.PROTECTED_KEYWORD) -> SemanticGraphVisibility.PROTECTED
    hasModifier(KtTokens.INTERNAL_KEYWORD) -> SemanticGraphVisibility.INTERNAL
    fqName == null -> SemanticGraphVisibility.LOCAL
    else -> SemanticGraphVisibility.PUBLIC
}

private fun KtNamedDeclaration.semanticModality(): SemanticGraphModality? = when {
    hasModifier(KtTokens.SEALED_KEYWORD) -> SemanticGraphModality.SEALED
    hasModifier(KtTokens.ABSTRACT_KEYWORD) -> SemanticGraphModality.ABSTRACT
    hasModifier(KtTokens.OPEN_KEYWORD) -> SemanticGraphModality.OPEN
    this is KtClassOrObject || this is KtNamedFunction || this is KtProperty -> SemanticGraphModality.FINAL
    else -> null
}

internal fun KtNamedDeclaration.semanticKey(path: SemanticGraphSourcePath): SemanticGraphSymbolKey = when (this) {
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

internal fun KastPluginBackend.semanticTarget(
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

internal fun KastPluginBackend.relativePathOr(
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

private fun KtNamedFunction.compilerStableSignature(): String? = analyze(this) { symbol.compilerStableSignature() }

internal fun KtConstructor<*>.compilerStableSignature(): String? =
    analyze(this) { symbol.compilerStableSignature() }

internal fun semanticConstructorKey(
    ownerKey: SemanticGraphSymbolKey,
    constructor: KtConstructor<*>,
    signature: String,
): SemanticGraphSymbolKey = SemanticGraphSymbolKey.parse(
    "constructor:${ownerKey.value}:${constructor.textRange.startOffset}:$signature",
)

internal fun KtNamedDeclaration.semanticCompilerRelations(): List<Pair<PsiElement, SemanticGraphRelationKind>> =
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
            if (this@semanticCompilerRelations.hasModifier(KtTokens.SEALED_KEYWORD)) {
                (declarationSymbol as? KaNamedClassSymbol)
                    ?.sealedClassInheritors
                    ?.mapNotNull { inheritor -> inheritor.psi }
                    ?.forEach { target -> add(target to SemanticGraphRelationKind.SEALED_MEMBER) }
            }
        }
    }

internal fun KaFunctionSymbol.compilerStableSignature(): String? {
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

internal fun projectableKind(declaration: KtNamedDeclaration): SemanticGraphSymbolKind? = when (declaration) {
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
    is KtParameter -> if (declaration.parent?.parent is KtFunctionType) null else SemanticGraphSymbolKind.VALUE_PARAMETER
    is KtTypeParameter -> SemanticGraphSymbolKind.TYPE_PARAMETER
    is KtTypeAlias -> SemanticGraphSymbolKind.TYPE_ALIAS
    else -> null
}

private fun nearestProjectedDeclaration(element: PsiElement?): KtNamedDeclaration? =
    generateSequence(element) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .firstOrNull { declaration -> projectableKind(declaration) != null }

internal fun nearestProjectedOwner(
    element: PsiElement,
    symbols: Map<KtNamedDeclaration, SemanticGraphSymbol>,
): SemanticGraphSymbol? = generateSequence(element.parent) { it.parent }
    .filterIsInstance<KtNamedDeclaration>()
    .firstNotNullOfOrNull(symbols::get)

internal fun relation(
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

internal fun fileKey(path: SemanticGraphSourcePath): SemanticGraphSymbolKey =
    SemanticGraphSymbolKey.parse("file:${path.value}")

private fun localKey(
    path: SemanticGraphSourcePath,
    declaration: KtNamedDeclaration,
    kind: SemanticGraphSymbolKind,
): SemanticGraphSymbolKey = SemanticGraphSymbolKey.parse(
    "local:${path.value}:${declaration.textRange.startOffset}:${kind.name}",
)
