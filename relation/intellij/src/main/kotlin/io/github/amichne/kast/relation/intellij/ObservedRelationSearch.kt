package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
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
    scope: SearchScope,
    ignoreAccessScope: Boolean = false,
    process: (PsiReference) -> Boolean,
): Boolean {
    com.intellij.openapi.progress.ProgressManager.checkCanceled()
    val partitions = if (scope is EnumeratedRelationScope) scope.referencePartitions() else sequenceOf(scope)
    for (partition in partitions) {
        val exhausted =
            search(IntellijReadSearch.REFERENCES) { search ->
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                ReferencesSearch.search(subject, partition, ignoreAccessScope)
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
        if (!exhausted) return false
    }
    return true
}

internal fun IntellijReadObservation.forEachDefinition(
    subject: PsiElement,
    scope: SearchScope,
    process: (PsiElement) -> Boolean,
): Boolean =
    search(IntellijReadSearch.DEFINITIONS) { search ->
        DefinitionsScopedSearch.search(subject, scope, false)
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
