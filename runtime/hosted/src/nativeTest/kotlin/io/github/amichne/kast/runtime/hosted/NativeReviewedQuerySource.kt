@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.query.protocol.ExactSelectorIssuance
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Each source copy issues fresh exact references through the production K2 symbol port. */
internal class NativeReviewedQuerySource(
    private val project: Project,
    private val file: VirtualFile,
    private val services: NativePacketQueryServices,
) {
    private val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.TEST_ONLY,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )

    fun assertNoErrors() = read {
        val errors =
            IntellijReadObservation.None.observedAnalyze(psi()) {
                psi()
                    .collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .filter { it.severity == KaSeverity.ERROR }
                    .map { it.factoryName }
            }
        check(errors.isEmpty()) { "R1 fixture compiler errors: $errors" }
    }

    suspend fun producer() =
        QueryImpactProducerDocument(
            enclosing = token(function("toClientValue")),
            callable = token(resolve("ClientValue") { checkNotNull(klass("ClientValue").primaryConstructor) }),
            anchor = reviewedQueryRange(REVIEWED_QUERY_SEED_START, REVIEWED_QUERY_SEED_END),
        )

    suspend fun declaration(name: String) = reference(function(name))

    suspend fun payload() =
        reference(
            resolve("payload") {
                klass("ClientEnvelope").primaryConstructorParameters.single { it.name == "payload" }
            }
        )

    private suspend fun function(name: String) =
        resolve(name) {
            psi().declarations.filterIsInstance<KtNamedFunction>().single { it.name == name }
        }

    private suspend fun resolve(name: String, declaration: () -> PsiNamedElement): SymbolSelector {
        val selected = read {
            val candidate =
                SymbolDiscoveryCandidate.fromBoundary(
                        kind = SymbolDiscoveryKind.SYMBOL,
                        rawName = name,
                        lease = services.lease,
                        nativePath = file.toNioPath(),
                        virtualFileUrl = file.url,
                        rawOffset = declaration().textRange.startOffset,
                    )
                    .packetQueryRefined()
            SymbolDiscoverySelection.restore(services.lease, scope, candidate).packetQueryRefined()
        }
        val resolved = services.symbols.exact.resolve(SymbolResolutionRequest(selected))
        check(resolved is SymbolResolutionCompilation.Resolved) { "R1 exact declaration failed: $name $resolved" }
        return resolved.selector
    }

    private fun token(selector: SymbolSelector): ProtocolText {
        val issued = services.references.issueExact(selector)
        check(issued is ExactSelectorIssuance.Issued) { "R1 exact reference issuance failed: $issued" }
        return issued.selector
    }

    private fun reference(selector: SymbolSelector) =
        ImpactDeclarationReferenceDocument(
            basis =
                ImpactSemanticBasisDocument.Published(
                    packetQueryText(services.lease.workspaceRoot.value),
                    ImpactEvidenceRevisionDocument.parse(services.lease.generation.value).packetQueryRefined(),
                ),
            file = packetQueryText(selector.file.stableValue),
            range = reviewedQueryRange(selector.range.startInclusive, selector.range.endExclusive),
            compilerIdentity = packetQueryText(selector.compilerIdentity.value),
        )

    private fun psi() = PsiManager.getInstance(project).findFile(file) as KtFile

    private fun klass(name: String) = psi().declarations.filterIsInstance<KtClass>().single { it.name == name }

    private fun <T> read(block: () -> T): T = ReadAction.compute<T, RuntimeException>(block)
}

internal fun reviewedQueryRange(start: Int, end: Int) =
    ImpactSourceRangeDocument(packetQueryOffset(start), packetQueryOffset(end))
