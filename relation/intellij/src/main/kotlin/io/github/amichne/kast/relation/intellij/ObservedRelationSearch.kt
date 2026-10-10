package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.call
import io.github.amichne.kast.workspace.intellij.read.search

/**
 * Counts the executed lazy query and every callback, including excluded sites. Native executor internals are opaque.
 */
internal fun IntellijReadObservation.forEachReference(
    subject: PsiElement,
    scope: GlobalSearchScope,
    admission: NativeRelationScopeAdmission,
    ignoreAccessScope: Boolean = false,
    process: (PsiReference) -> Boolean,
): Boolean =
    try {
        admission.check()
        val partitions =
            if (scope is EnumeratedRelationScope) scope.referencePartitions(admission) else sequenceOf(scope)
        var exhausted = true
        for (partition in partitions) {
            if (!referenceSearch(subject, { admission.wrap(partition) }, ignoreAccessScope, process)) {
                exhausted = false
                break
            }
        }
        exhausted
    } catch (_: NativeRelationScopeStopped) {
        false
    }

internal fun IntellijReadObservation.forEachReference(
    subject: PsiElement,
    scope: LocalSearchScope,
    ignoreAccessScope: Boolean = false,
    process: (PsiReference) -> Boolean,
): Boolean = referenceSearch(subject, { scope }, ignoreAccessScope, process)

private fun IntellijReadObservation.referenceSearch(
    subject: PsiElement,
    scope: () -> SearchScope,
    ignoreAccessScope: Boolean,
    process: (PsiReference) -> Boolean,
): Boolean =
    search(IntellijReadSearch.REFERENCES) { search ->
        com.intellij.openapi.progress.ProgressManager.checkCanceled()
        ReferencesSearch.search(subject, scope(), ignoreAccessScope)
            .forEach(
                Processor { reference ->
                    search.callbackEntered()
                    call(IntellijReadCall.REFERENCE_CALLBACK) {
                        com.intellij.openapi.progress.ProgressManager.checkCanceled()
                        process(reference)
                    }
                }
            )
    }

internal fun IntellijReadObservation.forEachDefinition(
    subject: PsiElement,
    scope: GlobalSearchScope,
    admission: NativeRelationScopeAdmission,
    process: (PsiElement) -> Boolean,
): Boolean =
    try {
        search(IntellijReadSearch.DEFINITIONS) { search ->
            DefinitionsScopedSearch.search(subject, admission.wrap(scope), false)
                .forEach(
                    Processor { provider ->
                        search.callbackEntered()
                        call(IntellijReadCall.DEFINITION_CALLBACK) {
                            com.intellij.openapi.progress.ProgressManager.checkCanceled()
                            process(provider)
                        }
                    }
                )
        }
    } catch (_: NativeRelationScopeStopped) {
        false
    }
