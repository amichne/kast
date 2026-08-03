@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal data class RenameSnapshot(
    val declarationEdit: TextEdit,
    val targetIdentity: SymbolIdentity,
    val generation: Long,
    val searchScope: GlobalSearchScope,
    val visibility: SymbolVisibility,
    val scopeKind: SearchScopeKind,
    val candidateFileCount: Int,
    val collectedReferenceCount: Int,
)

internal data class RenameReferencePlan(
    val occurrence: ReferenceOccurrence,
    val resolvedTarget: SymbolIdentity,
    val edit: TextEdit,
)
