package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class TopologyProjectionRegistryCacheTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `one exact candidate generation builds one detached projection registry`() {
        val candidates = candidates("a".repeat(64))
        val key = TopologyProjectionRegistryKey.from(candidates)
        val cache = TopologyProjectionRegistryCache()
        val builds = AtomicInteger()

        val first = cache.resolve(key) {
            builds.incrementAndGet()
            TopologyProjectionRegistryResolution.Ready(TopologyProjectionRegistry.empty(key))
        }
        val second = cache.resolve(key) {
            builds.incrementAndGet()
            TopologyProjectionRegistryResolution.Ready(TopologyProjectionRegistry.empty(key))
        }

        assertSame(first.ready(), second.ready())
        assertEquals(1, builds.get())
    }

    @Test
    fun `changed candidate evidence cannot reuse a detached projection registry`() {
        val original = TopologyProjectionRegistryKey.from(candidates("a".repeat(64)))
        val changed = TopologyProjectionRegistryKey.from(candidates("b".repeat(64)))
        val cache = TopologyProjectionRegistryCache()
        val builds = AtomicInteger()

        cache.resolve(original) {
            builds.incrementAndGet()
            TopologyProjectionRegistryResolution.Ready(
                TopologyProjectionRegistry.empty(original),
            )
        }
        val changedRegistry = cache.resolve(changed) {
            builds.incrementAndGet()
            TopologyProjectionRegistryResolution.Ready(
                TopologyProjectionRegistry.empty(changed),
            )
        }.ready()

        assertEquals(changed, changedRegistry.key)
        assertEquals(2, builds.get())
    }

    @Test
    fun `live source bytes must retain enumerated content identity`() {
        val original = "fun original() = Unit".toByteArray()
        val file = candidates(sha256(original)).files.single()

        assertInstanceOf(
            Refinement.Refined::class.java,
            LiveTopologySourceContent.validate(file, original),
        )
        val moved = assertInstanceOf(
            Refinement.Rejected::class.java,
            LiveTopologySourceContent.validate(file, "fun changed() = Unit".toByteArray()),
        )

        assertEquals(TopologySourceContentFailure.CONTENT_MOVED, moved.failure)
    }

    @Test
    fun `duplicate declaration locations cannot enter a projection registry`() {
        val candidates = candidates("a".repeat(64))
        val file = candidates.files.single()
        val key = TopologyProjectionRegistryKey.from(candidates)
        val first = symbol(file, "function|sample.first|-|||0")
        val second = symbol(file, "function|sample.second|-|||0")

        val rejected = assertInstanceOf(
            Refinement.Rejected::class.java,
            TopologyProjectionRegistry.from(key, listOf(first, second)),
        )

        assertEquals(
            TopologyProjectionRegistryFailure.DUPLICATE_DECLARATION_LOCATION,
            rejected.failure,
        )
    }

    private fun candidates(contentHash: String): TopologyCandidateSet {
        val root = SourceRoot.admit(
            GradleSourceRootEvidence(
                ideaModuleName = "topology.main",
                workspaceRelativeBuildRoot = ".",
                gradleProjectPath = ":topology",
                sourceSetName = "main",
                workspaceRelativeSourceRoot = "src/main/kotlin",
                provenance = SourceRootProvenance.Authored,
            ),
        ).refined()
        val workspace = workspace(root)
        val file = TopologySourceFile.admit(
            workspace,
            root,
            WorkspaceSourcePath.parse("src/main/kotlin/Example.kt").refined(),
            WorkspaceSourceContentHash.parse(contentHash).refined(),
        ).refined()
        return TopologyCandidateSet.admit(workspace, listOf(file)).refined()
    }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun symbol(file: TopologySourceFile, compilerIdentity: String): TopologySymbol {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
            file.workspace.lease.workspaceRoot,
            absolute,
            absolute.toUri().toString(),
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            fileIdentity,
            0,
            7,
            "example",
            "sample.example",
            CompilerSymbolKind.FUNCTION,
            CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "sample.example",
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf(compilerIdentity),
                rawTypeParameterCount = 0,
            ).refined(),
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun workspace(root: SourceRoot): PublishedWorkspace {
        val canonical = CanonicalWorkspaceRoot.fromCanonicalPath(tempDir.toRealPath()).refined()
        val candidate = WorkspaceCandidate(
            canonical,
            WorkspaceStateIdentity.parse("projection-registry-state").refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(root),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(1).refined())
    }

    private fun TopologyProjectionRegistryResolution.ready(): TopologyProjectionRegistry =
        when (this) {
            is TopologyProjectionRegistryResolution.Ready -> registry
            is TopologyProjectionRegistryResolution.Rejected -> error(failure.toString())
        }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
