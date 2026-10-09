package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.stubs.StubIndex
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

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
        is SymbolDiscoveryTarget.All -> false
        is SymbolDiscoveryTarget.Name ->
            selected.kind != SymbolNameDiscoveryKind.FILE && scope.scope.libraryPolicy() == SymbolLibraryPolicy.EXCLUDE
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

/** Ranked fuzzy-name enumeration retains its global name ranking; ALL uses the detached partition producer. */
internal fun collectScopedKotlinDeclarations(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    callbacks: ScopedDeclarationCallbacks,
    limits: io.github.amichne.kast.kernel.ReadLimits = io.github.amichne.kast.kernel.ReadLimits.Default,
    localOnly: Boolean = false,
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
            observation = callbacks.observation,
            localSourceOnly = localOnly,
        )
    val packageConstraint = request.constraints.packageName
    val complete =
        if (packageConstraint?.containment == SymbolDiscoveryContainment.DIRECT) {
            // Exact package membership is authoritative index evidence before file capacity; no package PSI in
            // callbacks.
            callbacks.observation.call(IntellijReadCall.EXACT_PACKAGE_INDEX) {
                StubIndex.getInstance().processElements(
                    KotlinExactPackagesIndex.NAME,
                    packageConstraint.packageName.value,
                    project,
                    scope.nativeScope,
                    KtFile::class.java,
                ) { file ->
                    files.accept(file.virtualFile)
                }
            }
        } else
            callbacks.observation.call(IntellijReadCall.SCOPED_FILE_INDEX) {
                FileTypeIndex.processFiles(KotlinFileType.INSTANCE, files::accept, scope.nativeScope)
            }
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
            observation = callbacks.observation,
            accept = callbacks.accept,
            localOnly = localOnly,
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
    private val localSourceOnly: Boolean = false,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    val values = ArrayList<VirtualFile>()
    var stop = ScopedFileCollectionStop.NONE
        private set

    fun accept(file: VirtualFile): Boolean =
        observation.call(IntellijReadCall.SCOPED_FILE_CALLBACK) {
            when {
                !observe() -> {
                    stop = ScopedFileCollectionStop.TIME_OR_ENVIRONMENT
                    false
                }
                !scope.nativeScope.contains(file) -> true
                localSourceOnly && !admittedLocalSource(file) -> true
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

    private fun admittedLocalSource(file: VirtualFile): Boolean =
        when (val path = nativePath(file)) {
            is IntellijVirtualFilePath.Absolute ->
                scope.sourceRoots.any { path.value.startsWith(java.nio.file.Path.of(it.sourceRoot.value)) }
            IntellijVirtualFilePath.Relative,
            IntellijVirtualFilePath.Unavailable -> false
        }
}

internal class ScopedKotlinDeclarationVisitor(
    private val manager: PsiManager,
    private val constraints: SymbolDiscoveryConstraints,
    private val kinds: Set<CompilerSymbolKind>,
    private val observe: () -> Boolean,
    private val qualify: (SymbolDiscoveryQualification) -> Unit,
    private val accept: (NavigationItem) -> Boolean,
    private val localOnly: Boolean = false,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    fun read(file: VirtualFile): Boolean {
        if (!observe()) return false
        val ktFile = observation.call(IntellijReadCall.PSI_FIND_FILE) { manager.findFile(file) } as? KtFile
        if (ktFile == null) {
            qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
            return true
        }
        return when (
            constraints.packageName.admitPackage { IntellijPackageEvidence.Known(ktFile.packageFqName.asString()) }
        ) {
            IntellijDiscoveryItemAdmission.ADMITTED ->
                observation.call(IntellijReadCall.DECLARATION_PSI_SCAN) { visit(ktFile) }
            IntellijDiscoveryItemAdmission.FILTERED -> true
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                true
            }
        }
    }

    private fun visit(file: KtFile): Boolean {
        var current = file.firstChild
        while (current != null) {
            if (!observe()) return false
            observation.count(IntellijReadCounter.DECLARATION_PSI_NODES_VISITED)
            if (current is KtNamedDeclaration && !admit(current)) return false
            current = current.firstChild ?: nextSiblingWithin(current, file)
        }
        return true
    }

    private fun admit(declaration: KtNamedDeclaration): Boolean {
        if (localOnly && !declaration.isSupportedLocal()) return true
        val kind = declaration.discoveryCompilerKind() ?: return true
        return kind !in kinds || accept(declaration)
    }

    private fun KtNamedDeclaration.isSupportedLocal(): Boolean =
        when (this) {
            is KtNamedFunction -> isLocal
            is KtProperty -> isLocal
            else -> false
        }

    private fun nextSiblingWithin(element: PsiElement, root: KtFile): PsiElement? {
        var current = element
        while (current !== root) {
            if (!observe()) return null
            current.nextSibling?.let {
                return it
            }
            current = current.parent ?: return null
        }
        return null
    }
}

internal data class ScopedDeclarationCallbacks(
    val observe: () -> Boolean,
    val qualify: (SymbolDiscoveryQualification) -> Unit,
    val accept: (NavigationItem) -> Boolean,
    val observation: IntellijReadObservation = IntellijReadObservation.None,
)
