@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** This probe distinguishes a binary module from its public library-source affiliation. Models remain NOT_RUN. */
class NativePacketLibraryAffiliationProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-library-affiliation-jdk"

    fun testOriginalFirstOrNullCapturesActualModuleAndBinaryAffiliation() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        ReadAction.run<RuntimeException> {
            val file =
                checkNotNull(
                    getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/feature/OffersProcessor.kt")
                )
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val call =
                PsiTreeUtil.collectElementsOfType(psi, KtCallExpression::class.java).single { candidate ->
                    candidate.calleeExpression?.text == "firstOrNull"
                }
            IntellijReadObservation.None.observedAnalyze(call) {
                val resolved = checkNotNull(call.resolveCall())
                val symbol = resolved.signature.symbol
                val projected = symbol.compilerProjection(this)
                assertTrue(projected is IntellijCompilerProjectionResult.Projected)
                val signature = (projected as IntellijCompilerProjectionResult.Projected).projection.signature
                assertEquals(
                    "kotlin.collections.firstOrNull",
                    (signature as CanonicalCompilerSignature.Function).qualifiedIdentity.value,
                )
                recordModule(symbol.containingModule)
                val declaration = symbol.psi as? PsiNamedElement
                println("LIBRARY_AFFILIATION_PSI url=${declaration?.containingFile?.virtualFile?.url?.take(MAX_URL)}")
                recordProjection(fixture, declaration)
            }
        }
        println("LIBRARY_AFFILIATION_RESULT status=CHARACTERIZED artifact_assertion=NOT_RUN models=NOT_RUN")
    }

    private fun recordModule(module: KaModule) {
        val actualKind =
            when (module) {
                is KaLibraryModule -> AffiliationModuleKind.LIBRARY
                is KaLibrarySourceModule -> AffiliationModuleKind.LIBRARY_SOURCE
                else -> AffiliationModuleKind.OTHER
            }
        println("LIBRARY_AFFILIATION_MODULE kind=$actualKind subtype=${module.javaClass.name.take(MAX_NAME)}")
        when (module) {
            is KaLibraryModule -> recordBinary(module)
            is KaLibrarySourceModule -> {
                println("LIBRARY_AFFILIATION_SOURCE name=${module.libraryName.take(MAX_NAME)} relation=BINARY_LIBRARY")
                recordBinary(module.binaryLibrary)
            }
            else -> println("LIBRARY_AFFILIATION_BINARY status=NO_PUBLIC_LIBRARY_RELATION")
        }
    }

    private fun recordBinary(module: KaLibraryModule) {
        val roots = module.binaryRoots.map { it.toAbsolutePath().normalize().toString() }
        val virtualRoots = module.binaryVirtualFiles.map { it.url }
        val artifact =
            Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                .toAbsolutePath()
                .normalize()
                .toString()
        println(
            "LIBRARY_AFFILIATION_BINARY name=${module.libraryName.take(MAX_NAME)} " +
                "subtype=${module.javaClass.name.take(MAX_NAME)} rootsCount=${roots.size} " +
                "roots=${roots.take(MAX_ROOTS).map { it.take(MAX_URL) }} exactArtifactMatch=${artifact in roots}"
        )
        println(
            "LIBRARY_AFFILIATION_VIRTUAL count=${virtualRoots.size} " +
                "urls=${virtualRoots.take(MAX_ROOTS).map { it.take(MAX_URL) }} " +
                "truncated=${roots.size > MAX_ROOTS || virtualRoots.size > MAX_ROOTS}"
        )
    }

    private fun recordProjection(fixture: NativePacketValueFixture, declaration: PsiNamedElement?) {
        if (declaration == null) {
            println("LIBRARY_AFFILIATION_PROJECTOR status=NO_NAMED_PSI")
            return
        }
        when (val projected = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)) {
            IntellijRelationDeclarationProjection.Unsupported ->
                println("LIBRARY_AFFILIATION_PROJECTOR status=UNSUPPORTED")
            is IntellijRelationDeclarationProjection.Projected ->
                when (val file = projected.evidence.file) {
                    is SymbolDiscoveryFileIdentity.External ->
                        println("LIBRARY_AFFILIATION_PROJECTOR status=EXTERNAL url=${file.url.value}")
                    is SymbolDiscoveryFileIdentity.Workspace ->
                        println("LIBRARY_AFFILIATION_PROJECTOR status=WORKSPACE path=${file.path.value}")
                }
        }
    }

    private companion object {
        const val MAX_NAME = 256
        const val MAX_URL = 2048
        const val MAX_ROOTS = 16
    }
}

private enum class AffiliationModuleKind {
    LIBRARY,
    LIBRARY_SOURCE,
    OTHER,
}
