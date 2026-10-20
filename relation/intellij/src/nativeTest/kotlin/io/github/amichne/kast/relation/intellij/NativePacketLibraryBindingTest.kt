@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointResolutionFailure
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** This capture characterizes exact native library bindings. It does not apply a semantic model. */
class NativePacketLibraryBindingTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-library-binding-jdk"

    fun testOriginalFirstOrNullBinding() = capture(LibraryCase.FIRST_OR_NULL)

    fun testOriginalLetBinding() = capture(LibraryCase.LET)

    fun testOriginalSinglePairMapOfBinding() = capture(LibraryCase.MAP_OF)

    fun testOriginalInfixToBinding() = capture(LibraryCase.TO)

    fun testOriginalEchoSingleElementListOfBinding() = capture(LibraryCase.LIST_OF)

    fun testOriginalV6FirstBinding() = capture(LibraryCase.FIRST)

    private fun capture(case: LibraryCase) {
        val fixture = packetFixture(case.paths, SymbolSourceKindPolicy.PRODUCTION_AND_TEST)
        val artifact = artifact()
        val observed =
            ReadAction.compute<LibraryBindingCapture, RuntimeException> {
                observe(fixture, expression(case), artifact)
            }
        val signature = observed.callable.signature as CanonicalCompilerSignature.Function
        assertEquals(case.identity, signature.qualifiedIdentity.value)
        assertEquals(observed.compilerSignature, signature)
        record(case, observed, artifact)
        assertEquals(PINNED_STDLIB_SHA256, artifact.sha256)
        assertEquals(PINNED_STDLIB_VERSION, artifact.version)
        assertAffiliation(observed)
        println("LIBRARY_BINDING_RESULT case=${case.name} capture=COMPLETE models=NOT_RUN")
    }

    private fun observe(
        fixture: NativePacketValueFixture,
        expression: KtExpression,
        artifact: LibraryArtifactCapture,
    ): LibraryBindingCapture =
        IntellijReadObservation.None.observedAnalyze(expression) {
            val resolved = checkNotNull(libraryCall(expression)) { "Library call did not resolve to one function call" }
            val symbol = resolved.signature.symbol as? KaNamedFunctionSymbol
            assertNotNull("Library call needs a named K2 callable", symbol)
            val callable = checkNotNull(symbol)
            val projected = callable.compilerProjection(this)
            assertTrue(
                "Compiler signature did not project: $projected",
                projected is IntellijCompilerProjectionResult.Projected,
            )
            val sourceLess = sourceLessCallable(callable)
            assertTrue(
                "Library source-less binding failed: $sourceLess",
                sourceLess is IntellijK2ResolvedDeclaration.SourceLess,
            )
            val module = callable.containingModule
            val roots =
                if (module is KaLibraryModule) module.binaryRoots.map { it.toAbsolutePath().normalize().toString() }
                else emptyList()
            val receiver = resolved.extensionReceiver as? KaExplicitReceiverValue
            LibraryBindingCapture(
                callable = (sourceLess as IntellijK2ResolvedDeclaration.SourceLess).callable,
                compilerSignature = (projected as IntellijCompilerProjectionResult.Projected).projection.signature,
                sourceRange = range(expression),
                receiverRange = receiver?.expression?.let(::range),
                arguments = resolved.valueArgumentMapping.keys.map(::range),
                parameterKinds =
                    callable.valueParameters.map {
                        if (it.isVararg) LibraryParameterKind.VARARG else LibraryParameterKind.FIXED
                    },
                diagnosticReturnType = callable.returnType.toString(),
                diagnosticExpressionType = expression.expressionType.toString(),
                libraryRoots = roots,
                affiliation = packetLibraryArtifactAffiliation(module, callable.psi, artifact.path),
                projection = endpoint(fixture, callable.psi as? PsiNamedElement),
            )
        }

    private fun KaSession.libraryCall(expression: KtExpression): KaFunctionCall<*>? =
        when (expression) {
            is KtCallExpression -> expression.resolveCall()
            is KtBinaryExpression -> expression.resolveCall() as? KaFunctionCall<*>
            else -> error("Library capture accepts only call and binary-expression fixtures")
        }

    private fun endpoint(fixture: NativePacketValueFixture, declaration: PsiNamedElement?): LibraryEndpointCapture {
        if (declaration == null) return LibraryEndpointCapture.NoNamedPsi
        return when (
            val projected = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
        ) {
            IntellijRelationDeclarationProjection.Unsupported -> LibraryEndpointCapture.Unsupported
            is IntellijRelationDeclarationProjection.Projected -> {
                val scope =
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.INCLUDE,
                    )
                when (val endpoint = RelationEndpoint.resolve(fixture.lease, scope, projected.evidence)) {
                    is Refinement.Refined ->
                        LibraryEndpointCapture.Resolved(endpoint.value.compilerIdentity, projected.evidence.file)
                    is Refinement.Rejected -> LibraryEndpointCapture.Rejected(endpoint.failure)
                }
            }
        }
    }

    private fun expression(case: LibraryCase): KtExpression {
        val file = psi(case.path)
        val declarations =
            if (case.owner.isEmpty()) file.declarations
            else
                PsiTreeUtil.collectElementsOfType(file, KtClass::class.java)
                    .single { it.name == case.owner }
                    .declarations
        val host = declarations.filterIsInstance<KtNamedFunction>().single { it.name == case.method }
        return if (case == LibraryCase.TO) {
            PsiTreeUtil.collectElementsOfType(host, KtBinaryExpression::class.java).single {
                it.operationReference.getReferencedName() == "to"
            }
        } else {
            PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single {
                it.calleeExpression?.text == case.callee
            }
        }
    }

    private fun psi(path: String): KtFile {
        val test = path.startsWith("src/test/kotlin/")
        val relative =
            (if (test) "test/" else "src/") + path.removePrefix(if (test) "src/test/kotlin/" else "src/main/kotlin/")
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(relative))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private fun artifact(): LibraryArtifactCapture {
        val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))
        val version = JarFile(jar.toFile()).use { it.manifest.mainAttributes.getValue("Implementation-Version") }
        return LibraryArtifactCapture(jar.toAbsolutePath().normalize().toString(), digest, version)
    }

    private fun record(case: LibraryCase, observed: LibraryBindingCapture, artifact: LibraryArtifactCapture) {
        val signature = observed.callable.signature as CanonicalCompilerSignature.Function
        println(
            "LIBRARY_BINDING case=${case.name} anchor=${observed.sourceRange} " +
                "receiverAnchor=${observed.receiverRange} " +
                "argumentAnchors=${observed.arguments} identity=${observed.callable.compilerIdentity.value}"
        )
        println(
            "LIBRARY_SIGNATURE case=${case.name} callable=${signature.qualifiedIdentity.value} " +
                "receiver=${signature.receiver} contexts=${signature.contextReceivers.map { it.value }} " +
                "parameters=${signature.valueParameters.map { it.value }} " +
                "parameterKinds=${observed.parameterKinds} typeParameters=${signature.typeParameterCount.value} " +
                "declaredResultDiagnostic=${observed.diagnosticReturnType} " +
                "actualResultDiagnostic=${observed.diagnosticExpressionType}"
        )
        println(
            "LIBRARY_AUTHORITY case=${case.name} origin=${observed.callable.origin} " +
                "module=${observed.callable.moduleKind} " +
                "moduleName=${observed.callable.moduleName.value} roots=${observed.libraryRoots} " +
                "projectorStatus=${observed.projection.status}"
        )
        recordEndpoint(case, observed.projection)
        println(
            "LIBRARY_ARTIFACT case=${case.name} sha256=${artifact.sha256} version=${artifact.version} " +
                "moduleVirtualRoots=${observed.affiliation.moduleRoots} " +
                "moduleRootMatch=${observed.affiliation.artifactRoot in observed.affiliation.moduleRoots} " +
                "declarationInArtifact=${observed.affiliation.containsDeclaration}"
        )
    }

    private fun recordEndpoint(case: LibraryCase, endpoint: LibraryEndpointCapture) {
        when (endpoint) {
            LibraryEndpointCapture.NoNamedPsi -> println("LIBRARY_ENDPOINT case=${case.name} status=NO_NAMED_PSI")
            LibraryEndpointCapture.Unsupported -> println("LIBRARY_ENDPOINT case=${case.name} status=UNSUPPORTED")
            is LibraryEndpointCapture.Resolved ->
                println(
                    "LIBRARY_ENDPOINT case=${case.name} status=RESOLVED identity=${endpoint.identity.value} " +
                        "file=${endpoint.file.stableValue}"
                )
            is LibraryEndpointCapture.Rejected ->
                println("LIBRARY_ENDPOINT case=${case.name} status=REJECTED cause=${endpoint.cause}")
        }
    }

    private fun assertAffiliation(observed: LibraryBindingCapture) {
        val affiliation = observed.affiliation
        assertTrue(
            "The module must expose the exact pinned virtual artifact root",
            affiliation.artifactRoot in affiliation.moduleRoots,
        )
        assertTrue("The resolved declaration must belong to that exact artifact", affiliation.containsDeclaration)
        assertTrue(
            "Artifact admission needs the real projected endpoint",
            observed.projection is LibraryEndpointCapture.Resolved,
        )
        val endpoint = observed.projection as LibraryEndpointCapture.Resolved
        assertTrue(
            "A library declaration must keep its external file identity",
            endpoint.file is SymbolDiscoveryFileIdentity.External,
        )
        assertEquals(affiliation.declarationUrl, (endpoint.file as SymbolDiscoveryFileIdentity.External).url.value)
    }

    private companion object {
        const val PINNED_STDLIB_SHA256 = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val PINNED_STDLIB_VERSION = "2.3.20-release-208"
    }
}

private enum class LibraryParameterKind {
    FIXED,
    VARARG,
}

private enum class LibraryProjectionStatus {
    NO_NAMED_PSI,
    UNSUPPORTED,
    RESOLVED,
    REJECTED,
}

private sealed interface LibraryEndpointCapture {
    val status: LibraryProjectionStatus

    data object NoNamedPsi : LibraryEndpointCapture {
        override val status = LibraryProjectionStatus.NO_NAMED_PSI
    }

    data object Unsupported : LibraryEndpointCapture {
        override val status = LibraryProjectionStatus.UNSUPPORTED
    }

    data class Resolved(val identity: CompilerSymbolIdentity, val file: SymbolDiscoveryFileIdentity) :
        LibraryEndpointCapture {
        override val status = LibraryProjectionStatus.RESOLVED
    }

    data class Rejected(val cause: RelationEndpointResolutionFailure) : LibraryEndpointCapture {
        override val status = LibraryProjectionStatus.REJECTED
    }
}

private data class LibraryArtifactCapture(val path: String, val sha256: String, val version: String?)

private data class LibraryBindingCapture(
    val callable: SourceLessCallable,
    val compilerSignature: CanonicalCompilerSignature,
    val sourceRange: ExactDeclarationTextRange,
    val receiverRange: ExactDeclarationTextRange?,
    val arguments: List<ExactDeclarationTextRange>,
    val parameterKinds: List<LibraryParameterKind>,
    val diagnosticReturnType: String,
    val diagnosticExpressionType: String,
    val libraryRoots: List<String>,
    val affiliation: NativePacketLibraryArtifactAffiliation,
    val projection: LibraryEndpointCapture,
)

private enum class LibraryCase(
    val path: String,
    val owner: String,
    val method: String,
    val callee: String,
    val identity: String,
) {
    FIRST_OR_NULL(
        path = FEATURE,
        owner = "OffersProcessorImpl",
        method = "getOffer",
        callee = "firstOrNull",
        identity = "kotlin.collections.firstOrNull",
    ),
    LET(path = OUTCOME, owner = "", method = "requireNotNull", callee = "let", identity = "kotlin.let"),
    MAP_OF(
        path = CLIENT,
        owner = "HeaderBuilder",
        method = "build",
        callee = "mapOf",
        identity = "kotlin.collections.mapOf",
    ),
    TO(path = CLIENT, owner = "HeaderBuilder", method = "build", callee = "to", identity = "kotlin.to"),
    LIST_OF(
        path = ECHO,
        owner = "EchoOffersClient",
        method = "readOffers",
        callee = "listOf",
        identity = "kotlin.collections.listOf",
    ),
    FIRST(
        path = V6,
        owner = "BackendClient",
        method = "fetch",
        callee = "first",
        identity = "kotlin.collections.first",
    );

    val paths: List<String>
        get() =
            when (this) {
                LET -> listOf(OUTCOME)
                FIRST -> listOf(V6)
                LIST_OF -> listOf(OUTCOME, WIRE, CLIENT, ECHO)
                FIRST_OR_NULL -> listOf(OUTCOME, WIRE, CLIENT, FEATURE)
                MAP_OF,
                TO -> listOf(OUTCOME, WIRE, CLIENT)
            }
}

private const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
private const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
private const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
private const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
private const val ECHO = "src/test/kotlin/fixture/client/EchoOffersClient.kt"
private const val V6 = "src/main/kotlin/fixture/variants/v6/V6.kt"
