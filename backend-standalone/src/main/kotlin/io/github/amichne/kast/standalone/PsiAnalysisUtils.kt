package io.github.amichne.kast.standalone

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.api.FqName
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.PackageName
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolVisibility
import io.github.amichne.kast.api.TextEdit
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.nio.file.Path

/**
 * Canonical way to extract a [NormalizedPath] from a PSI element's containing file.
 * Prefers [com.intellij.psi.PsiFile.getVirtualFile] and falls back to
 * [com.intellij.psi.FileViewProvider.getVirtualFile] for in-memory files.
 */
internal fun PsiElement.resolvedFilePath(): NormalizedPath {
    val vf = containingFile.virtualFile ?: containingFile.viewProvider.virtualFile
    return NormalizedPath.of(Path.of(vf.path))
}

/**
 * Walks the PSI element hierarchy up from [offset] until it finds a resolvable reference
 * or a named element, then returns it.
 */
internal fun resolveTarget(file: com.intellij.psi.PsiFile, offset: Int): PsiElement {
    val leaf = file.findElementAt(offset)
        ?: throw NotFoundException(
            message = "No PSI element was found at the requested offset",
            details = mapOf("offset" to offset.toString()),
        )

    generateSequence(leaf as PsiElement?) { it.parent }.forEach { element ->
        element.references.firstNotNullOfOrNull { it.resolve() }?.let { return it }

        if (element is PsiNamedElement && !element.name.isNullOrBlank()) {
            return element
        }
    }

    throw NotFoundException("No resolvable symbol was found at the requested offset")
}

internal fun PsiElement.toSymbolModel(
    containingDeclaration: String?,
    supertypes: List<String>? = null,
): Symbol = Symbol(
    fqName = fqName(),
    kind = kind(),
    location = toKastLocation(nameRange()),
    type = typeDescription(),
    containingDeclaration = containingDeclaration,
    supertypes = supertypes,
    visibility = visibility(),
)

private fun PsiElement.nameRange(): TextRange = when (this) {
    is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
    is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
    else -> textRange
}

internal fun PsiElement.declarationEdit(newName: String): TextEdit {
    val range = nameRange()
    return TextEdit(
        filePath = resolvedFilePath().value,
        startOffset = range.startOffset,
        endOffset = range.endOffset,
        newText = newName,
    )
}

// @Serializable  are going to unwrap the value classes by default, so we can just use the typed value here without needing to manually extract the underlying string.

/**
 * Extracts the effective visibility of a PSI element.
 *
 * Kotlin declarations without an explicit modifier default to [SymbolVisibility.PUBLIC]
 * at the top level, but declarations nested inside a function body or block expression
 * are classified as [SymbolVisibility.LOCAL] since they are unreachable outside the
 * enclosing scope.
 *
 * Java package-private (no modifier) maps to [SymbolVisibility.INTERNAL] as the closest
 * Kotlin analog.
 */
internal fun PsiElement.visibility(): SymbolVisibility = when (this) {
    is KtNamedDeclaration -> ktVisibility()
    is PsiClass -> javaClassVisibility()
    is PsiMethod -> javaMemberVisibility()
    is PsiField -> javaMemberVisibility()
    else -> SymbolVisibility.UNKNOWN
}

private fun KtNamedDeclaration.ktVisibility(): SymbolVisibility = when {
    hasModifier(KtTokens.PRIVATE_KEYWORD) -> SymbolVisibility.PRIVATE
    hasModifier(KtTokens.INTERNAL_KEYWORD) -> SymbolVisibility.INTERNAL
    hasModifier(KtTokens.PROTECTED_KEYWORD) -> SymbolVisibility.PROTECTED
    hasModifier(KtTokens.PUBLIC_KEYWORD) -> SymbolVisibility.PUBLIC
    isLocalDeclaration() -> SymbolVisibility.LOCAL
    else -> SymbolVisibility.PUBLIC // Kotlin default for top-level and class members
}

private fun KtNamedDeclaration.isLocalDeclaration(): Boolean =
    parentsWithSelf().any { parent ->
        parent !== this && parent is KtDeclarationWithBody
    }

private fun PsiClass.javaClassVisibility(): SymbolVisibility = when {
    hasModifierProperty(PsiModifier.PRIVATE) -> SymbolVisibility.PRIVATE
    hasModifierProperty(PsiModifier.PROTECTED) -> SymbolVisibility.PROTECTED
    hasModifierProperty(PsiModifier.PUBLIC) -> SymbolVisibility.PUBLIC
    else -> SymbolVisibility.INTERNAL // Java package-private ≈ internal
}

private fun PsiElement.javaMemberVisibility(): SymbolVisibility = when {
    this is PsiModifierListOwner && hasModifierProperty(PsiModifier.PRIVATE) -> SymbolVisibility.PRIVATE
    this is PsiModifierListOwner && hasModifierProperty(PsiModifier.PROTECTED) -> SymbolVisibility.PROTECTED
    this is PsiModifierListOwner && hasModifierProperty(PsiModifier.PUBLIC) -> SymbolVisibility.PUBLIC
    else -> SymbolVisibility.INTERNAL // Java package-private ≈ internal
}

private fun PsiElement.fqName(): String = when (this) {
    is KtNamedDeclaration -> fqName?.asString() ?: name ?: "<anonymous>"
    is PsiClass -> qualifiedName ?: name ?: "<anonymous>"
    is PsiMethod -> "${containingClass?.qualifiedName ?: "<local>"}#$name"
    is PsiField -> "${containingClass?.qualifiedName ?: "<local>"}.$name"
    is PsiNamedElement -> name ?: "<anonymous>"
    else -> text
}

/**
 * Returns the fully qualified name and containing package of a target element,
 * or `null` when either cannot be determined (e.g. anonymous or local declarations).
 *
 * For class members (methods, properties, fields), the package is derived from
 * the containing file or class rather than from the member FQ name, so that
 * import-aware filtering in [MutableSourceIdentifierIndex.candidatePathsForFqName]
 * matches correctly against file-level package declarations.
 */
internal fun PsiElement.targetFqNameAndPackage(): Pair<FqName, PackageName>? {
    val fqn: String
    val pkg: String
    when (this) {
        is KtNamedDeclaration -> {
            fqn = fqName?.asString() ?: return null
            pkg = (containingFile as? org.jetbrains.kotlin.psi.KtFile)
                ?.packageFqName?.asString()
                ?: fqn.substringBeforeLast('.', missingDelimiterValue = "")
        }
        is PsiClass -> {
            fqn = qualifiedName ?: return null
            pkg = fqn.substringBeforeLast('.', missingDelimiterValue = "")
        }
        is PsiMethod -> {
            val classFqn = containingClass?.qualifiedName ?: return null
            fqn = "$classFqn.$name"
            pkg = classFqn.substringBeforeLast('.', missingDelimiterValue = "")
        }
        is PsiField -> {
            val classFqn = containingClass?.qualifiedName ?: return null
            fqn = "$classFqn.$name"
            pkg = classFqn.substringBeforeLast('.', missingDelimiterValue = "")
        }
        else -> return null
    }
    return FqName(fqn) to PackageName(pkg)
}

private fun PsiElement.kind(): SymbolKind = when (this) {
    is KtClass -> if (isInterface()) SymbolKind.INTERFACE else SymbolKind.CLASS
    is KtObjectDeclaration -> SymbolKind.OBJECT
    is KtNamedFunction -> SymbolKind.FUNCTION
    is KtProperty -> SymbolKind.PROPERTY
    is KtParameter -> SymbolKind.PARAMETER
    is PsiClass -> if (isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS
    is PsiMethod -> SymbolKind.FUNCTION
    is PsiField -> SymbolKind.PROPERTY
    else -> SymbolKind.UNKNOWN
}

private fun PsiElement.typeDescription(): String? = when (this) {
    is KtNamedFunction -> typeReference?.text
    is KtProperty -> typeReference?.text
    is KtParameter -> typeReference?.text
    is PsiMethod -> returnType?.presentableText
    is PsiField -> type.presentableText
    else -> null
}

/**
 * Converts a PSI element and text range to a [Location] using raw file text.
 */
internal fun PsiElement.toKastLocation(range: TextRange = nameRange()): Location {
    val file = containingFile
    val content = file.viewProvider.contents
    val startOffset = range.startOffset.coerceIn(0, content.length)
    val endOffset = range.endOffset.coerceIn(startOffset, content.length)
    val lineStart = content.lastIndexOf('\n', startOffset - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = content.indexOf('\n', startOffset).let { if (it == -1) content.length else it }

    return Location(
        filePath = resolvedFilePath().value,
        startOffset = startOffset,
        endOffset = endOffset,
        startLine = content.subSequence(0, startOffset).count { it == '\n' } + 1,
        startColumn = startOffset - lineStart + 1,
        preview = content.substring(lineStart, lineEnd).trimEnd(),
    )
}

internal fun PsiElement.parentsWithSelf(): Sequence<PsiElement> = generateSequence(this) { it.parent }

internal fun PsiElement.callHierarchyDeclaration(): PsiElement? = parentsWithSelf().firstOrNull { element ->
    when (element) {
        is KtNamedFunction,
        is KtProperty,
        is KtClass,
        is KtObjectDeclaration,
        is PsiMethod,
        is PsiField,
        is PsiClass,
        -> true

        else -> false
    }
}

internal fun PsiElement.typeHierarchyDeclaration(): PsiElement? = parentsWithSelf().firstOrNull { element ->
    when (element) {
        is KtClassOrObject,
        is PsiClass,
        -> true

        else -> false
    }
}

internal fun KaSession.supertypeNames(target: PsiElement): List<String>? = when (target) {
    is KtClassOrObject -> target.classSymbol
        ?.superTypes
        ?.mapNotNull { type -> (type as? KaClassType)?.classId?.asSingleFqName()?.asString() }
        ?.distinct()
        ?.sorted()

    is PsiClass -> target.supers
        .mapNotNull(PsiClass::getQualifiedName)
        .distinct()
        .sorted()

    else -> null
}

internal fun PsiElement.referenceSearchIdentifier(): String? = when (this) {
    is KtNamedFunction -> name.takeUnless { hasModifier(KtTokens.OPERATOR_KEYWORD) }
    is KtNamedDeclaration -> name
    else -> (this as? PsiNamedElement)?.name
}
    ?.takeIf { identifier -> identifier.isNotBlank() }
