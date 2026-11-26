package io.github.amichne.kast.symbol.intellij

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy

/** Both exact-name routes feed source declarations into the same bounded admission and compiler projection. */
internal fun collectExactJavaDeclarations(
    project: Project,
    request: SymbolDiscoveryRequest,
    scope: GlobalSearchScope,
    accept: (NavigationItem) -> Boolean,
): Boolean {
    val target = request.target
    if (target !is SymbolDiscoveryTarget.Name || target.match != SymbolDiscoveryMatch.EXACT_NAME) return true
    val name = target.pattern.value
    val kinds = request.requestedDeclarationKinds()
    val javaScope = javaSelectionScope(request, name, scope)
    fun admit(member: PsiMember): Boolean {
        val membership = request.constraints.packageName.admitPackage(member::indexedPackage)
        return membership == IntellijDiscoveryItemAdmission.FILTERED || accept(member)
    }
    val index = StubIndex.getInstance()
    if (
        CompilerSymbolKind.CLASSLIKE in kinds &&
            !index.processElements(
                JavaStubIndexKeys.CLASS_SHORT_NAMES,
                name,
                project,
                javaScope,
                PsiClass::class.java,
                ::admit,
            )
    )
        return false
    return (CompilerSymbolKind.FUNCTION !in kinds ||
        index.processElements(JavaStubIndexKeys.METHODS, name, project, javaScope, PsiMethod::class.java) {
            it.isConstructor || admit(it)
        }) &&
        // No declaration kind selects a field or an enum constant. Admission qualifies the match instead of hiding it.
        index.processElements(JavaStubIndexKeys.FIELDS, name, project, javaScope, PsiField::class.java) {
            it.selectionFamily() !in kinds || admit(it)
        }
}

/** A library search also selects compiled Java members that Kotlin can reach only by their own name. */
private fun javaSelectionScope(
    request: SymbolDiscoveryRequest,
    name: String,
    scope: GlobalSearchScope,
): GlobalSearchScope =
    if (request.scope.scope.libraryPolicy() == SymbolLibraryPolicy.INCLUDE && javaNameHasOnlyItsOwnKotlinSyntax(name))
        GlobalSearchScope.getScopeRestrictedByFileTypes(scope, JavaFileType.INSTANCE, JavaClassFileType.INSTANCE)
    else GlobalSearchScope.getScopeRestrictedByFileTypes(scope, JavaFileType.INSTANCE)

/** Kotlin reads a Java field as a property and an enum constant as an enum entry. */
private fun PsiField.selectionFamily(): CompilerSymbolKind =
    if (this is PsiEnumConstant) CompilerSymbolKind.CLASSLIKE else CompilerSymbolKind.PROPERTY

/**
 * Kotlin names a Java member by other syntax in two cases. An accessor (`getX`, `isX`, `setX`) reads as the property
 * `x`. A convention name (`plus`, `get`, `invoke`, `componentN`, ...) also answers an operator or delegate. Every other
 * Java member name appears in Kotlin source only as itself, so selecting it by that name misses no use.
 */
internal fun javaNameHasOnlyItsOwnKotlinSyntax(name: String): Boolean =
    !name.isJavaAccessorShaped() && name !in KOTLIN_CONVENTION_NAMES && !COMPONENT_NAME.matches(name)

private fun String.isJavaAccessorShaped(): Boolean =
    listOf("get", "set", "is").any { prefix ->
        length > prefix.length && startsWith(prefix) && this[prefix.length].isUpperCase()
    }

private val COMPONENT_NAME = Regex("component[1-9][0-9]*")

private val KOTLIN_CONVENTION_NAMES =
    setOf(
        "plus",
        "minus",
        "times",
        "div",
        "rem",
        "mod",
        "rangeTo",
        "rangeUntil",
        "unaryPlus",
        "unaryMinus",
        "not",
        "inc",
        "dec",
        "contains",
        "get",
        "set",
        "invoke",
        "compareTo",
        "equals",
        "iterator",
        "next",
        "hasNext",
        "plusAssign",
        "minusAssign",
        "timesAssign",
        "divAssign",
        "remAssign",
        "modAssign",
        "getValue",
        "setValue",
        "provideDelegate",
    )

/**
 * A compiled Java declaration that Kotlin can name through other syntax cannot be selected by its own name. Its
 * presence in the requested package therefore leaves a library search incomplete rather than empty. A Java accessor
 * whose Kotlin property name is the requested name does the same, because Kotlin source writes that name for it. Kotlin
 * class files have no Java stubs, so these indexes never report a Kotlin declaration.
 */
internal fun hasUnselectableJavaDeclaration(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
): Boolean {
    if (scope.scope.libraryPolicy() != SymbolLibraryPolicy.INCLUDE) return false
    val name = (request.target as? SymbolDiscoveryTarget.Name)?.pattern?.value ?: return false
    val compiled = GlobalSearchScope.getScopeRestrictedByFileTypes(scope.nativeScope, JavaClassFileType.INSTANCE)
    // Processing stops at the first declaration that the package constraint does not exclude.
    fun excluded(member: PsiMember): Boolean =
        request.constraints.packageName.admitPackage(member::indexedPackage) == IntellijDiscoveryItemAdmission.FILTERED
    val index = StubIndex.getInstance()
    fun present(key: String): Boolean =
        !(index.processElements(
            JavaStubIndexKeys.CLASS_SHORT_NAMES,
            key,
            project,
            compiled,
            PsiClass::class.java,
            ::excluded,
        ) &&
            index.processElements(
                JavaStubIndexKeys.METHODS,
                key,
                project,
                compiled,
                PsiMethod::class.java,
                ::excluded,
            ) &&
            index.processElements(JavaStubIndexKeys.FIELDS, key, project, compiled, PsiField::class.java, ::excluded))
    if (!javaNameHasOnlyItsOwnKotlinSyntax(name)) return present(name)
    return accessorsNamedAsProperty(name).any { accessor ->
        !index.processElements(
            JavaStubIndexKeys.METHODS,
            accessor,
            project,
            compiled,
            PsiMethod::class.java,
            ::excluded,
        )
    }
}

/**
 * Every `getS` or `setS` that Kotlin reads as the property [name]. Kotlin decapitalizes `S` smartly, so `getURL` reads
 * as `url` and `getURLConnection` as `urlConnection`. An `isS` accessor keeps its own name and is accessor-shaped
 * already. A name that starts with an upper-case letter is never a synthetic property name.
 */
internal fun accessorsNamedAsProperty(name: String): List<String> {
    if (name.isEmpty() || !name[0].isLowerCase()) return emptyList()
    val suffixes =
        (1..name.length)
            .map { length -> name.take(length).uppercase() + name.drop(length) }
            .filter { it.decapitalizeSmart() == name }
            .distinct()
    return suffixes.flatMap { listOf("get$it", "set$it") }
}

/** Kotlin's synthetic property name rule: lower the leading upper-case run, except a last capital before lower case. */
private fun String.decapitalizeSmart(): String {
    if (isEmpty() || !this[0].isUpperCase()) return this
    if (length == 1 || !this[1].isUpperCase()) return this[0].lowercaseChar() + substring(1)
    val run = indexOfFirst { !it.isUpperCase() }.let { if (it < 0) length else it }
    if (run == length) return lowercase()
    return substring(0, run - 1).lowercase() + substring(run - 1)
}

/** The outer class stub supplies its package without a package PSI read inside an index callback. */
private fun PsiMember.indexedPackage(): IntellijPackageEvidence {
    val owner = if (this is PsiClass) this else containingClass
    val qualified = generateSequence(owner) { it.containingClass }.lastOrNull()?.qualifiedName
    return if (qualified == null) IntellijPackageEvidence.Unavailable
    else IntellijPackageEvidence.Known(qualified.substringBeforeLast('.', ""))
}
