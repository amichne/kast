package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.impl.VirtualFileEnumeration
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call

/** Foreign search control flow only; the caller records its closed limitation before returning HALTED. */
internal class NativeRelationScopeStopped : ProcessCanceledException()

/** Native filters run before provider delivery, so even an excluded file must observe the original allowance. */
internal class NativeRelationScopeAdmission(
    private val observation: IntellijReadObservation,
    private val admit: () -> IntellijRelationProviderEnumerationAdmission,
) {
    fun check() {
        observation.call(IntellijReadCall.RELATION_SCOPE_CALLBACK_ADMISSION) {
            when (admit()) {
                IntellijRelationProviderEnumerationAdmission.READY ->
                    observation.count(IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_READY)
                IntellijRelationProviderEnumerationAdmission.HALTED -> {
                    observation.count(IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_HALTED)
                    throw NativeRelationScopeStopped()
                }
            }
        }
    }

    fun wrap(base: GlobalSearchScope): GlobalSearchScope {
        check()
        return when (val enumeration = VirtualFileEnumeration.extract(base)) {
            null -> Scope(base, this)
            else -> EnumeratedScope(base, enumeration, this)
        }
    }

    private open class Scope(base: GlobalSearchScope, protected val admission: NativeRelationScopeAdmission) :
        DelegatingGlobalSearchScope(base, admission) {
        override fun contains(file: VirtualFile): Boolean {
            admission.check()
            return delegate.contains(file)
        }

        override fun isSearchInModuleContent(module: Module): Boolean {
            admission.check()
            return delegate.isSearchInModuleContent(module)
        }

        override fun isSearchInModuleContent(module: Module, testSources: Boolean): Boolean {
            admission.check()
            return delegate.isSearchInModuleContent(module, testSources)
        }

        override fun isSearchInLibraries(): Boolean {
            admission.check()
            return delegate.isSearchInLibraries
        }

        override fun isForceSearchingInLibrarySources(): Boolean {
            admission.check()
            return delegate.isForceSearchingInLibrarySources
        }

        override fun compare(first: VirtualFile, second: VirtualFile): Int {
            admission.check()
            return delegate.compare(first, second)
        }

        override fun intersectWith(scope: GlobalSearchScope): GlobalSearchScope =
            admission.wrap(delegate.intersectWith(scope))
    }

    private class EnumeratedScope(
        base: GlobalSearchScope,
        private val enumeration: VirtualFileEnumeration,
        admission: NativeRelationScopeAdmission,
    ) : Scope(base, admission), VirtualFileEnumeration {
        override fun contains(fileId: Int): Boolean {
            admission.check()
            return enumeration.contains(fileId)
        }

        override fun asArray(): IntArray {
            admission.check()
            return enumeration.asArray()
        }

        override fun getFilesIfCollection(): Collection<VirtualFile>? {
            admission.check()
            return when (enumeration) {
                is EnumeratedRelationScope -> enumeration.getFilesIfCollection(admission)
                else -> enumeration.filesIfCollection
            }
        }
    }
}
