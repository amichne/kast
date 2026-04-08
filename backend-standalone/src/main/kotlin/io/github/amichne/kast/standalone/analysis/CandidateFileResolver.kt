package io.github.amichne.kast.standalone.analysis

import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.SearchScope
import io.github.amichne.kast.api.SearchScopeKind
import io.github.amichne.kast.api.SymbolVisibility
import io.github.amichne.kast.standalone.StandaloneAnalysisSession
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import org.jetbrains.kotlin.psi.KtFile

internal class CandidateFileResolver(
    private val session: StandaloneAnalysisSession,
    private val telemetry: StandaloneTelemetry = StandaloneTelemetry.disabled(),
) {
    fun resolve(target: PsiElement): CandidateSearchResult {
        val visibility = target.visibility()

        if (visibility == SymbolVisibility.PRIVATE || visibility == SymbolVisibility.LOCAL) {
            return fileScopedResult(
                declaringFile = target.containingFile as? KtFile,
                visibility = visibility,
            )
        }

        val declaringFile = target.containingFile as? KtFile
        val anchorFilePath = target.resolvedFilePath().value
        val searchIdentifier = target.referenceSearchIdentifier()
            ?: return resolveWithoutIdentifier(
                declaringFile = declaringFile,
                visibility = visibility,
                anchorFilePath = anchorFilePath,
            )

        val fqNameAndPackage = target.targetFqNameAndPackage()
        val candidatePaths = if (fqNameAndPackage != null) {
            val (targetFqName, targetPackage) = fqNameAndPackage
            session.candidateKotlinFilePathsForFqName(
                identifier = searchIdentifier,
                anchorFilePath = anchorFilePath,
                targetPackage = targetPackage,
                targetFqName = targetFqName,
            )
        } else {
            session.candidateKotlinFilePaths(
                identifier = searchIdentifier,
                anchorFilePath = anchorFilePath,
            )
        }

        val anchorSourceModuleName = session.sourceModuleNameForFile(anchorFilePath)

        logCandidateResolution(
            searchIdentifier = searchIdentifier,
            anchorFilePath = anchorFilePath,
            anchorSourceModuleName = anchorSourceModuleName?.value,
            friendModuleNames = anchorSourceModuleName?.let { session.friendModuleNames(it).map { m -> m.value }.toSet() },
            candidateCountBefore = candidatePaths.size,
        )

        if (candidatePaths.isEmpty()) {
            return fileScopedResult(
                declaringFile = declaringFile,
                visibility = visibility,
            )
        }

        if (visibility == SymbolVisibility.INTERNAL) {
            val declaringModuleName = session.sourceModuleNameForFile(anchorFilePath)
            if (declaringModuleName != null) {
                val friendNames = session.friendModuleNames(declaringModuleName)
                val moduleFiltered = candidatePaths
                    .filter { candidatePath -> session.sourceModuleNameForFile(candidatePath) in friendNames }
                if (moduleFiltered.isNotEmpty()) {
                    return CandidateSearchResult(
                        files = moduleFiltered.map(session::findKtFile),
                        scope = SearchScope(
                            visibility = visibility,
                            scope = SearchScopeKind.MODULE,
                            exhaustive = true,
                            candidateFileCount = candidatePaths.size,
                            searchedFileCount = moduleFiltered.size,
                        ),
                    )
                }
            }
        }

        val capped = candidatePaths.capCandidateFiles(searchIdentifier)
        return CandidateSearchResult(
            files = capped.map(session::findKtFile),
            scope = SearchScope(
                visibility = visibility,
                scope = SearchScopeKind.DEPENDENT_MODULES,
                exhaustive = capped.size == candidatePaths.size,
                candidateFileCount = candidatePaths.size,
                searchedFileCount = capped.size,
            ),
        )
    }

    private fun logCandidateResolution(
        searchIdentifier: String,
        anchorFilePath: String,
        anchorSourceModuleName: String?,
        friendModuleNames: Set<String>?,
        candidateCountBefore: Int,
    ) {
        telemetry.inSpan(
            scope = StandaloneTelemetryScope.REFERENCES,
            name = "kast.candidateFileResolver",
            attributes = mapOf(
                "kast.resolver.searchIdentifier" to searchIdentifier,
                "kast.resolver.anchorFilePath" to anchorFilePath,
                "kast.resolver.anchorSourceModuleName" to (anchorSourceModuleName ?: "null"),
                "kast.resolver.friendModuleNames" to (friendModuleNames?.joinToString(",") ?: "null — returning all candidates"),
                "kast.resolver.candidateCountBefore" to candidateCountBefore,
            ),
        ) {}
    }

    private fun resolveWithoutIdentifier(
        declaringFile: KtFile?,
        visibility: SymbolVisibility,
        anchorFilePath: String,
    ): CandidateSearchResult {
        val allFiles = session.allKtFiles()
        if (allFiles.isEmpty()) {
            return fileScopedResult(
                declaringFile = declaringFile,
                visibility = visibility,
            )
        }

        if (visibility == SymbolVisibility.INTERNAL) {
            val declaringModuleName = session.sourceModuleNameForFile(anchorFilePath)
            if (declaringModuleName != null) {
                val friendNames = session.friendModuleNames(declaringModuleName)
                val moduleFiltered = allFiles.filter { candidateFile ->
                    session.sourceModuleNameForFile(candidateFile.resolvedFilePath().value) in friendNames
                }
                if (moduleFiltered.isNotEmpty()) {
                    return CandidateSearchResult(
                        files = moduleFiltered,
                        scope = SearchScope(
                            visibility = visibility,
                            scope = SearchScopeKind.MODULE,
                            exhaustive = true,
                            candidateFileCount = allFiles.size,
                            searchedFileCount = moduleFiltered.size,
                        ),
                    )
                }
            }
        }

        return CandidateSearchResult(
            files = allFiles,
            scope = SearchScope(
                visibility = visibility,
                scope = SearchScopeKind.DEPENDENT_MODULES,
                exhaustive = true,
                candidateFileCount = allFiles.size,
                searchedFileCount = allFiles.size,
            ),
        )
    }

    private fun fileScopedResult(
        declaringFile: KtFile?,
        visibility: SymbolVisibility,
    ): CandidateSearchResult {
        val files = listOfNotNull(declaringFile)
        return CandidateSearchResult(
            files = files,
            scope = SearchScope(
                visibility = visibility,
                scope = SearchScopeKind.FILE,
                exhaustive = true,
                candidateFileCount = files.size,
                searchedFileCount = files.size,
            ),
        )
    }
}

internal data class CandidateSearchResult(
    val files: List<KtFile>,
    val scope: SearchScope,
)

private const val MAX_CANDIDATE_FILES = 500

internal fun List<String>.capCandidateFiles(identifier: String): List<String> {
    if (size <= MAX_CANDIDATE_FILES) {
        return this
    }
    System.err.println(
        "kast candidate file cap: identifier '$identifier' matched $size files, capping to $MAX_CANDIDATE_FILES",
    )
    return take(MAX_CANDIDATE_FILES)
}
