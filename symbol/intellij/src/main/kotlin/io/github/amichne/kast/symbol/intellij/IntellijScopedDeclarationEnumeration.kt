package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.stubs.StubIndex
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/** Cheap retained constraints choose the index and declaration families before any native work. */
internal fun SymbolDiscoveryRequest.requestedDeclarationKinds(): Set<CompilerSymbolKind> =
    constraints.declarationKinds?.values?.toSet()
        ?: when (target.discoveryKind()) {
            SymbolNameDiscoveryKind.CLASS -> setOf(CompilerSymbolKind.CLASSLIKE)
            SymbolNameDiscoveryKind.SYMBOL ->
                setOf(
                    CompilerSymbolKind.CLASSLIKE,
                    CompilerSymbolKind.FUNCTION,
                    CompilerSymbolKind.PROPERTY,
                    CompilerSymbolKind.TYPE_ALIAS,
                )
            SymbolNameDiscoveryKind.FILE -> emptySet()
        }

internal fun SymbolDiscoveryRequest.usesScopedDeclarationEnumeration(): Boolean =
    when (val selected = target) {
        is SymbolDiscoveryTarget.All -> selected.kind != SymbolNameDiscoveryKind.FILE
        is SymbolDiscoveryTarget.Name ->
            selected.kind != SymbolNameDiscoveryKind.FILE &&
                selected.match == SymbolDiscoveryMatch.FUZZY &&
                scope.scope.libraryPolicy() == SymbolLibraryPolicy.EXCLUDE
        else -> false
    }

/** Prune provider families before their project-wide key scans when library/file policy requires contributors. */
internal fun SymbolDiscoveryRequest.admitsContributorName(name: String): Boolean {
    val kind = target.discoveryKind()
    if (!kind.isAdmittedContributorName(name)) return false
    if (kind == SymbolNameDiscoveryKind.FILE) return true
    val family =
        when (name) {
            "org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor",
            "org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor" -> CompilerSymbolKind.CLASSLIKE
            "org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor" -> CompilerSymbolKind.FUNCTION
            "org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor" -> CompilerSymbolKind.PROPERTY
            "org.jetbrains.kotlin.idea.goto.KotlinGotoTypeAliasContributor" -> CompilerSymbolKind.TYPE_ALIAS
            else -> return false
        }
    return family in requestedDeclarationKinds()
}

/** File-index membership precedes PSI; kind and package admission precede candidate capacity and K2. */
internal fun collectScopedKotlinDeclarations(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    callbacks: ScopedDeclarationCallbacks,
    limits: io.github.amichne.kast.kernel.ReadLimits = io.github.amichne.kast.kernel.ReadLimits.Default,
): Boolean {
    val fileLimit =
        (io.github.amichne.kast.kernel.WorkUnitLimit.parse(
                limits[io.github.amichne.kast.kernel.ReadLimitParameter.DISCOVERY_FILES].value.toLong()
            ) as io.github.amichne.kast.kernel.Refinement.Refined)
            .value
    val files =
        ScopedKotlinFileCollection(
            scope = scope,
            workLimit = fileLimit,
            observe = callbacks.observe,
            qualify = callbacks.qualify,
        )
    val packageConstraint = request.constraints.packageName
    val complete =
        if (packageConstraint?.containment == SymbolDiscoveryContainment.DIRECT) {
            // Exact package membership is authoritative index evidence before file capacity; no package PSI in
            // callbacks.
            StubIndex.getInstance().processElements(
                KotlinExactPackagesIndex.NAME,
                packageConstraint.packageName.value,
                project,
                scope.nativeScope,
                KtFile::class.java,
            ) { file ->
                files.accept(file.virtualFile)
            }
        } else FileTypeIndex.processFiles(KotlinFileType.INSTANCE, files::accept, scope.nativeScope)
    if (!complete && files.stop == ScopedFileCollectionStop.NONE)
        callbacks.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
    // Native callbacks have ended before any PSI package inspection or declaration traversal.
    val visitor =
        ScopedKotlinDeclarationVisitor(
            manager = PsiManager.getInstance(project),
            constraints = request.constraints,
            kinds = request.requestedDeclarationKinds(),
            observe = callbacks.observe,
            qualify = callbacks.qualify,
            accept = callbacks.accept,
        )
    return files.values.sortedBy { it.path }.all(visitor::read)
}

internal enum class ScopedFileCollectionStop {
    NONE,
    TIME_OR_ENVIRONMENT,
    WORK_LIMIT,
}

/** Capacity is spent only after authoritative file membership, before PSI is acquired. */
internal class ScopedKotlinFileCollection(
    private val scope: CompiledIntellijSearchScope,
    private val workLimit: io.github.amichne.kast.kernel.WorkUnitLimit,
    private val observe: () -> Boolean,
    private val qualify: (SymbolDiscoveryQualification) -> Unit,
) {
    val values = ArrayList<VirtualFile>()
    var stop = ScopedFileCollectionStop.NONE
        private set

    fun accept(file: VirtualFile): Boolean =
        when {
            !observe() -> {
                stop = ScopedFileCollectionStop.TIME_OR_ENVIRONMENT
                false
            }
            !scope.nativeScope.contains(file) -> true
            values.size.toLong() >= workLimit.value -> {
                stop = ScopedFileCollectionStop.WORK_LIMIT
                qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                false
            }
            else -> {
                values += file
                true
            }
        }
}

private class ScopedKotlinDeclarationVisitor(
    private val manager: PsiManager,
    private val constraints: SymbolDiscoveryConstraints,
    private val kinds: Set<CompilerSymbolKind>,
    private val observe: () -> Boolean,
    private val qualify: (SymbolDiscoveryQualification) -> Unit,
    private val accept: (NavigationItem) -> Boolean,
) {
    fun read(file: VirtualFile): Boolean {
        if (!observe()) return false
        val ktFile = manager.findFile(file) as? KtFile
        if (ktFile == null) {
            qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
            return true
        }
        return when (
            constraints.packageName.admitPackage { IntellijPackageEvidence.Known(ktFile.packageFqName.asString()) }
        ) {
            IntellijDiscoveryItemAdmission.ADMITTED ->
                ktFile.declarations.all { it !is KtNamedDeclaration || visit(it) }
            IntellijDiscoveryItemAdmission.FILTERED -> true
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                true
            }
        }
    }

    private fun visit(declaration: KtNamedDeclaration): Boolean {
        if (!observe()) return false
        return when (declaration) {
            is KtClassOrObject -> visitClass(declaration)
            is KtNamedFunction -> CompilerSymbolKind.FUNCTION !in kinds || accept(declaration)
            is KtProperty -> CompilerSymbolKind.PROPERTY !in kinds || accept(declaration)
            is KtTypeAlias -> CompilerSymbolKind.TYPE_ALIAS !in kinds || accept(declaration)
            else -> true
        }
    }

    private fun visitClass(declaration: KtClassOrObject): Boolean {
        // Enum entries inherit KtClassOrObject but are not supported class declarations.
        if (declaration !is KtEnumEntry) {
            if (CompilerSymbolKind.CLASSLIKE in kinds && !accept(declaration)) return false
            if (CompilerSymbolKind.PROPERTY in kinds && !constructorProperties(declaration)) return false
        }
        // Excluded containers can own eligible members; pruning the container cannot prune its subtree.
        return declaration.declarations.all { it !is KtNamedDeclaration || visit(it) }
    }

    private fun constructorProperties(declaration: KtClassOrObject): Boolean =
        declaration.primaryConstructorParameters.all { observe() && (!it.hasValOrVar() || accept(it)) }
}

internal data class ScopedDeclarationCallbacks(
    val observe: () -> Boolean,
    val qualify: (SymbolDiscoveryQualification) -> Unit,
    val accept: (NavigationItem) -> Boolean,
)
