package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call

/**
 * Counts the executed lazy query and every callback, including excluded sites. Native executor internals are opaque.
 */
internal fun IntellijReadObservation.forEachReference(
    subject: PsiElement,
    scope: SearchScope,
    ignoreAccessScope: Boolean = false,
    process: (PsiReference) -> Boolean,
): Boolean =
    call(IntellijReadCall.REFERENCE_SEARCH) {
        ReferencesSearch.search(subject, scope, ignoreAccessScope)
            .forEach(
                Processor { reference ->
                    call(IntellijReadCall.REFERENCE_CALLBACK) {
                        com.intellij.openapi.progress.ProgressManager.checkCanceled()
                        process(reference)
                    }
                }
            )
    }

internal fun IntellijReadObservation.forEachDefinition(
    subject: PsiElement,
    scope: SearchScope,
    process: (PsiElement) -> Boolean,
): Boolean =
    call(IntellijReadCall.DEFINITION_SEARCH) {
        DefinitionsScopedSearch.search(subject, scope, false)
            .forEach(
                Processor { provider ->
                    call(IntellijReadCall.DEFINITION_CALLBACK) {
                        com.intellij.openapi.progress.ProgressManager.checkCanceled()
                        process(provider)
                    }
                }
            )
    }
