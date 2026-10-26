@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOwnershipDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceProvenanceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRootDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.query.protocol.ExactSelectorIssuance
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
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
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression

/** Source positions remain locators until the production exact-symbol port resolves them through K2. */
internal class NativePacketQuerySource(
    private val project: Project,
    private val file: VirtualFile,
    private val services: NativePacketQueryServices,
    val variant: NativePacketQueryVariant,
    private val ownedRoot: ModelOwnedSourceRoot,
) {
    private val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_ONLY,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )

    fun assertNoErrors() = read {
        val errors =
            IntellijReadObservation.None.observedAnalyze(psi()) {
                val module = useSiteModule
                check(module is KaSourceModule) { "Packet query has no source-module settings" }
                val settings = module.languageVersionSettings
                println(
                    "PACKET_COMPILER_SETTINGS module=${module.name} " +
                        "language=${settings.languageVersion} api=${settings.apiVersion}"
                )
                val features = LanguageFeature.entries.joinToString { "$it=${settings.getFeatureSupport(it)}" }
                println("PACKET_COMPILER_FEATURES $features")
                psi()
                    .collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .filter { it.severity == KaSeverity.ERROR }
                    .map { it.factoryName }
            }
        check(errors.isEmpty()) { "Packet query fixture source errors: $errors" }
    }

    suspend fun producer(outgoing: Boolean): QueryImpactProducerDocument {
        val owner = if (outgoing) "Feature" else variant.concreteClient
        val method = if (outgoing) "handle" else "fetch"
        val callee = if (outgoing) "ClientRequest" else "send"
        val callable = if (outgoing) declaration("ClientRequest", "<init>") else declaration("Transport", "send")
        return QueryImpactProducerDocument(
            token(declaration(owner, method)),
            token(callable),
            invocation(owner, method, callee),
        )
    }

    suspend fun producer(owner: String, method: String, callee: String) =
        QueryImpactProducerDocument(
            token(declaration(owner, method)),
            token(declaration("", callee)),
            invocation(owner, method, callee),
        )

    fun markedRange(marker: String): ImpactSourceRangeDocument = read {
        val text = psi().text
        val opening = "/*<$marker>*/"
        val closing = "/*</$marker>*/"
        check(text.indexOf(opening) >= 0 && text.indexOf(opening) == text.lastIndexOf(opening))
        check(text.indexOf(closing) >= 0 && text.indexOf(closing) == text.lastIndexOf(closing))
        reviewedQueryRange(text.indexOf(opening) + opening.length, text.indexOf(closing))
    }

    suspend fun declaration(owner: String, member: String): SymbolSelector {
        val source = read {
            val declaration: PsiNamedElement =
                if (member == "<init>") checkNotNull(klass(owner).primaryConstructor) else function(owner, member)
            candidate(declaration, if (member == "<init>") owner else member)
        }
        val result = services.symbols.exact.resolve(SymbolResolutionRequest(source))
        check(result is SymbolResolutionCompilation.Resolved) {
            "Packet declaration prerequisite $owner.$member: $result"
        }
        return result.selector.also {
            check(it.kind == if (member == "<init>") CompilerSymbolKind.CONSTRUCTOR else CompilerSymbolKind.FUNCTION)
        }
    }

    suspend fun field(owner: String, name: String): ImpactDeclarationReferenceDocument {
        val selected = read { candidate(klass(owner).primaryConstructorParameters.single { it.name == name }, name) }
        val result = services.symbols.exact.resolve(SymbolResolutionRequest(selected))
        check(result is SymbolResolutionCompilation.Resolved) { "Packet field prerequisite $owner.$name: $result" }
        check(result.selector.kind == CompilerSymbolKind.PROPERTY)
        return reference(result.selector)
    }

    fun propertyRange(owner: String, name: String) = read {
        range(klass(owner).primaryConstructorParameters.single { it.name == name })
    }

    fun expressionRange(owner: String, method: String, expression: String): ImpactSourceRangeDocument = read {
        val body = checkNotNull(function(owner, method).bodyExpression)
        val offset = body.text.indexOf(expression)
        check(offset >= 0 && offset == body.text.lastIndexOf(expression))
        val start = body.textRange.startOffset + offset
        reviewedQueryRange(start, start + expression.length)
    }

    fun propertyIndex(owner: String, name: String) = read {
        packetQueryOffset(klass(owner).primaryConstructorParameters.indexOfFirst { it.name == name })
    }

    suspend fun ordinaryParameter(owner: String, method: String, name: String): SymbolResolutionCompilation {
        val selected = read { candidate(function(owner, method).valueParameters.single { it.name == name }, name) }
        return services.symbols.exact.resolve(SymbolResolutionRequest(selected))
    }

    fun invocation(owner: String, method: String, callee: String): ImpactSourceRangeDocument = read {
        val call = call(owner, method, callee)
        val qualified = call.parent as? KtDotQualifiedExpression
        range(if (qualified?.selectorExpression == call) qualified else call)
    }

    fun outgoingArgument(): ImpactSourceRangeDocument = read {
        range(
            checkNotNull(call(variant.concreteClient, "fetch", "send").valueArguments.single().getArgumentExpression())
        )
    }

    fun featureReturn(): ImpactSourceRangeDocument = read {
        range(
            checkNotNull(
                PsiTreeUtil.collectElementsOfType(function("Feature", "handle"), KtReturnExpression::class.java)
                    .single()
                    .returnedExpression
            )
        )
    }

    suspend fun outgoingSite() =
        ImpactValueSiteReferenceDocument(
            enclosing = reference(declaration(variant.concreteClient, "fetch")),
            range = outgoingArgument(),
            role =
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(
                        invocation(variant.concreteClient, "fetch", "send"),
                        reference(declaration("Transport", "send")),
                    ),
                    packetQueryOffset(0),
                ),
        )

    suspend fun featureSite() =
        ImpactValueSiteReferenceDocument(
            reference(declaration("Feature", "handle")),
            featureReturn(),
            ImpactValueRoleDocument.Return,
        )

    suspend fun requestedDeclarations(outgoing: Boolean): List<QueryImpactDeclarationDocument> {
        val selectors =
            if (outgoing) {
                listOf(variant.concreteClient, "BackendClient").distinct().map { declaration(it, "fetch") } +
                    declaration("Transport", "send")
            } else {
                listOf(declaration("Feature", "handle"))
            }
        return selectors.map { selector -> QueryImpactDeclarationDocument(token(selector), reference(selector)) }
    }

    fun reference(selector: SymbolSelector) =
        ImpactDeclarationReferenceDocument(
            basis =
                ImpactSemanticBasisDocument.Published(
                    packetQueryText(services.lease.workspaceRoot.value),
                    ImpactEvidenceRevisionDocument.parse(services.lease.generation.value).packetQueryRefined(),
                ),
            file = packetQueryText(selector.file.stableValue),
            range =
                ImpactSourceRangeDocument(
                    packetQueryOffset(selector.range.startInclusive),
                    packetQueryOffset(selector.range.endExclusive),
                ),
            compilerIdentity = packetQueryText(selector.compilerIdentity.value),
        )

    fun ownership(declaration: ImpactDeclarationReferenceDocument) =
        ImpactSourceOwnershipDocument(
            declaration,
            ImpactSourceRootDocument(
                module = packetQueryText(ownedRoot.module.value),
                buildRoot = packetQueryText(ownedRoot.project.buildRoot.value),
                projectPath = packetQueryText(ownedRoot.project.projectPath.value),
                sourceSet = packetQueryText(ownedRoot.sourceSet.value),
                sourceRoot = packetQueryText(ownedRoot.sourceRoot.value),
                sourceKind = ImpactSourceKindDocument.PRODUCTION,
                provenance = ImpactSourceProvenanceDocument.AUTHORED,
            ),
        )

    private fun token(selector: SymbolSelector): ProtocolText {
        val issued = services.references.issueExact(selector)
        check(issued is ExactSelectorIssuance.Issued) { "Packet selector issuance failed: $issued" }
        return issued.selector
    }

    private fun candidate(declaration: PsiNamedElement, name: String): SymbolDiscoverySelection {
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    kind = SymbolDiscoveryKind.SYMBOL,
                    rawName = name,
                    lease = services.lease,
                    nativePath = file.toNioPath(),
                    virtualFileUrl = file.url,
                    rawOffset = declaration.textRange.startOffset,
                )
                .packetQueryRefined()
        return SymbolDiscoverySelection.restore(services.lease, scope, candidate).packetQueryRefined()
    }

    private fun psi() = PsiManager.getInstance(project).findFile(file) as KtFile

    private fun klass(name: String) =
        PsiTreeUtil.collectElementsOfType(psi(), KtClass::class.java).single { it.name == name }

    private fun function(owner: String, name: String): KtNamedFunction {
        val declarations = if (owner.isEmpty()) psi().declarations else klass(owner).declarations
        return declarations.filterIsInstance<KtNamedFunction>().single { it.name == name }
    }

    private fun call(owner: String, method: String, callee: String) =
        PsiTreeUtil.collectElementsOfType(function(owner, method), KtCallExpression::class.java).single {
            it.calleeExpression?.text == callee
        }

    private fun range(element: PsiElement) =
        ImpactSourceRangeDocument(
            packetQueryOffset(element.textRange.startOffset),
            packetQueryOffset(element.textRange.endOffset),
        )

    private fun <T> read(block: () -> T): T = ReadAction.compute<T, RuntimeException>(block)
}

internal fun packetQueryText(value: String) = ProtocolText.parse(value).packetQueryRefined()

internal fun packetQueryOffset(value: Int) = ProtocolOffset.parse(value).packetQueryRefined()
