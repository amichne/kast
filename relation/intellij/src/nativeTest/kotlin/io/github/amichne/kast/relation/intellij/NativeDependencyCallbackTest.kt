package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.relation.contract.StaticCallbackNode
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking

/** Real stdlib bindings under the same authored source domain, with independent formal-position assertions. */
class NativeDependencyCallbackTest : NativeDependencyCallbackFixture() {
    override val fixtureSdkName = "dependency-callback-case-jdk"

    override fun runInDispatchThread() = false

    fun testReceiverLambdaWithoutAttachedSources() = assertDependency("kotlin.with(Unit) { proof.target() }", false, 1)

    fun testReceiverLambdaWithAttachedSources() = assertDependency("kotlin.with(Unit) { proof.target() }", true, 1)

    fun testOrdinaryLambdaWithoutAttachedSources() = assertDependency("Unit.also { proof.target() }", false, 0)

    fun testOrdinaryLambdaWithAttachedSources() = assertDependency("Unit.also { proof.target() }", true, 0)

    fun testNamedArgumentsPreserveActualToFormalPosition() =
        assertDependency("kotlin.with(block = { proof.target() }, receiver = Unit)", true, 1)

    fun testLazyInitializerHasACompleteDeferredSupplyWithoutAnImmediateInvocation() {
        val fixture = fixture("kotlin.lazy { proof.target() }", false)
        ReadAction.run<RuntimeException> {
            val flow = read(fixture)
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertFalse(flow.binding is CallbackBindingEvidence.DependencyContract)
        }
    }

    fun testLazyPropertyKeepsItsInitializerOwner() {
        val fixture = fixture("Unit", false, "val client by kotlin.lazy { proof.target() }\n")
        ReadAction.run<RuntimeException> {
            val flow = read(fixture, ownerName = "client")
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertTrue(flow.binding is CallbackBindingEvidence.DeferredDependency)
        }
    }

    fun testLazyInitializerWithSources() = assertDeferred("kotlin.lazy { proof.target() }", true, 0)

    fun testBuildRuntimeStdlibKeepsADeferredSupply() {
        val jar =
            System.getProperty("java.class.path").split(java.io.File.pathSeparator).map(Path::of).single {
                it.fileName.toString() == "kotlin-stdlib-2.4.21.jar"
            }
        assertDeferred(
            expression = "kotlin.lazy { proof.target() }",
            sources = false,
            expectedPosition = 0,
            libraryJar = jar,
        )
    }

    fun testLazyPublicationKeepsADeferredSupply() =
        assertDeferred("kotlin.lazy(kotlin.LazyThreadSafetyMode.PUBLICATION) { proof.target() }", false, 1)

    fun testLazyNoneModeKeepsADeferredSupply() =
        assertDeferred("kotlin.lazy(kotlin.LazyThreadSafetyMode.NONE) { proof.target() }", false, 1)

    fun testLazyNamedArgumentsKeepTheFormalPosition() =
        assertDeferred(
            "kotlin.lazy(initializer = { proof.target() }, mode = kotlin.LazyThreadSafetyMode.SYNCHRONIZED)",
            true,
            1,
        )

    fun testLazyExplicitLockKeepsADeferredSupply() =
        assertDeferred("kotlin.lazy(lock = Unit, initializer = { proof.target() })", true, 1)

    fun testLazyShadowCannotBorrowTheDeferredModel() {
        val fixture = fixture("lazy { proof.target() }", true, "fun lazy(initializer: () -> Int) = 0\n")
        ReadAction.run<RuntimeException> {
            val flow = read(fixture)
            assertTrue(flow.binding is CallbackBindingEvidence.Bound)
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
        }
    }

    fun testChangedLazyImplementationRejectsWithItsModelOutcome() {
        val root = java.nio.file.Files.createTempDirectory("kast-lazy-model-case-")
        val changed = root.resolve("kotlin-stdlib.jar")
        try {
            changedLazyLibrary(changed)
            val fixture = fixture("kotlin.lazy { proof.target() }", false, libraryJar = changed)
            ReadAction.run<RuntimeException> {
                val observation = DependencyProofObservation()
                val flow = read(fixture, observation)
                assertEquals(setOf(CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN), flow.obligations)
                assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
                assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
                assertEquals(1, observation.counts[IntellijReadCounter.CALLBACK_DEFERRED_MODELS_REJECTED])
                assertEquals(
                    listOf(IntellijReadTermination.CALLBACK_DEPENDENCY_DEFERRED_MODEL_UNPROVEN),
                    observation.reasons,
                )
            }
        } finally {
            java.nio.file.Files.deleteIfExists(changed)
            java.nio.file.Files.deleteIfExists(root)
        }
    }

    private fun changedLazyLibrary(destination: Path) {
        val original = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
        ZipFile(original.toFile()).use { input ->
            java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(destination)).use { output ->
                copyLazyEntries(input, output)
            }
        }
    }

    private fun copyLazyEntries(input: ZipFile, output: java.util.zip.ZipOutputStream) {
        input.entries().asSequence().forEach { entry ->
            val bytes = input.getInputStream(entry).use { it.readBytes() }
            changeLazyDebugMetadata(entry.name, bytes)
            output.putNextEntry(java.util.zip.ZipEntry(entry.name))
            output.write(bytes)
            output.closeEntry()
        }
    }

    private fun changeLazyDebugMetadata(name: String, bytes: ByteArray) {
        if (name != "kotlin/UnsafeLazyImpl.class") return
        val offset = bytes.toString(Charsets.ISO_8859_1).indexOf("Lazy.kt")
        assertTrue("The owned fixture must keep a valid SourceFile constant", offset >= 0)
        bytes[offset + "Lazy.".length] = 'x'.code.toByte()
    }

    private fun assertDeferred(expression: String, sources: Boolean, expectedPosition: Int, libraryJar: Path? = null) {
        val fixture = fixture(expression, sources, libraryJar = libraryJar)
        ReadAction.run<RuntimeException> {
            val observation = DependencyProofObservation()
            val flow = read(fixture, observation)
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertTrue(flow.binding is CallbackBindingEvidence.DeferredDependency)
            val binding = (flow.binding as CallbackBindingEvidence.DeferredDependency).binding
            assertEquals(expectedPosition, binding.position.value)
            if (libraryJar != null) {
                val file = binding.target.file as SymbolDiscoveryFileIdentity.External
                assertTrue(file.url.value.startsWith("jar://$libraryJar!/"))
            }

            assertEquals(fixture.request.subject.lease.identity, binding.basis)
            assertEquals(1, observation.counts[IntellijReadCounter.CALLBACK_DEFERRED_MODELS_ADMITTED])
            assertEquals(emptyList<IntellijReadTermination>(), observation.reasons)
        }
    }

    // The stable R0 case identity predates support for the compiler-declared UNKNOWN effect.
    fun testMissingExactlyOnceEffectKeepsTheFirstFailedProof() {
        val fixture = fixture("kotlin.repeat(0) { proof.target() }", true)
        ReadAction.run<RuntimeException> {
            assertRepeatCompilerEffect(checkNotNull(psiManager.findFile(fixture.callerFile)))
            val observation = DependencyProofObservation()
            val flow = read(fixture, observation)
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            val binding = (flow.binding as CallbackBindingEvidence.DependencyContract).binding
            assertEquals(
                CallbackDependencyInvocationKind.UNKNOWN,
                binding.invocationKind,
            )
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertEquals(1, observation.counts[IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_ADMITTED])
            assertEquals(emptyList<IntellijReadTermination>(), observation.reasons)
        }
    }

    fun testSameNameWithoutInvocationCannotBorrowTheLibraryEffect() {
        val fixture =
            fixture(
                "with(Unit) { proof.target() }",
                true,
                "fun with(receiver: Unit, block: Unit.() -> Unit) {}\n",
            )
        ReadAction.run<RuntimeException> {
            val flow = read(fixture)
            assertTrue(flow.binding is CallbackBindingEvidence.Bound)
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
        }
    }

    fun testReceiverLambdaGraphPreservesExactOccurrenceAndSourceNodes() {
        val expression = "kotlin.with(Unit) { proof.target() }"
        val fixture = fixture(expression, true)
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers) }
        assertTrue(
            "Expected exhausted compiler read, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val batch = (compilation as RelationCompilation.Complete).batch
        assertEquals(1, batch.callbackObservations.size)
        val observed = batch.callbackObservations.single()
        val admitted = CompleteStaticCallbackGraph.admit(observed)
        assertTrue(
            "Expected static graph, received $admitted",
            admitted is io.github.amichne.kast.kernel.Refinement.Refined,
        )
        val graph = (admitted as io.github.amichne.kast.kernel.Refinement.Refined).value
        assertEquals(
            listOf(StaticCallbackEdge.DependencyContractInvoke::class, StaticCallbackEdge.BodyTarget::class),
            graph.edges.map { it::class },
        )
        assertEquals(EXPECTED_STATIC_NODES, graph.nodes.size)
        assertTrue(
            graph.nodes.all { node ->
                when (node) {
                    is StaticCallbackNode.Named -> node.callable.file is SymbolDiscoveryFileIdentity.Workspace
                    is StaticCallbackNode.Anonymous -> node.body.file is SymbolDiscoveryFileIdentity.Workspace
                    is StaticCallbackNode.ClassReference,
                    is StaticCallbackNode.DeferredSupplier,
                    is StaticCallbackNode.Formal -> false
                }
            }
        )
        val text = "package consumer\nfun caller() { $expression }\n"
        val callStart = text.indexOf("target()")
        assertEquals(callStart, observed.occurrence.range.startInclusive)
        assertEquals(callStart + "target".length, observed.occurrence.range.endExclusive)
    }

    private fun assertDependency(expression: String, sources: Boolean, expectedPosition: Int) {
        val fixture = fixture(expression, sources)
        ReadAction.run<RuntimeException> {
            val observation = DependencyProofObservation()
            val flow = read(fixture, observation)
            assertEquals(
                "Dependency proof must discharge only its static obligation",
                emptySet<CallbackInvocationFlowCause>(),
                flow.obligations,
            )
            assertEquals(CallbackInvocationScan.EXHAUSTIVE, flow.scan)
            assertTrue(
                "Expected binary contract, received ${flow.binding}",
                flow.binding is CallbackBindingEvidence.DependencyContract,
            )
            val contract = (flow.binding as CallbackBindingEvidence.DependencyContract).binding
            assertEquals(expectedPosition, contract.position.value)
            assertEquals(fixture.request.subject.lease.identity, contract.basis)
            assertEquals(flow.body.file, contract.occurrence.file)
            val targetFile = contract.target.file as SymbolDiscoveryFileIdentity.External
            assertTrue(targetFile.url.value.endsWith(".class"))
            val parts = targetFile.url.value.removePrefix("jar://").split("!/", limit = 2)
            val expectedDigest =
                ZipFile(parts[0]).use { jar ->
                    val bytes = jar.getInputStream(checkNotNull(jar.getEntry(parts[1]))).use { it.readBytes() }
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                }
            assertEquals(expectedDigest, contract.classDigest.value)
            assertEquals(emptyList<CallbackParameterInvocation>(), flow.invocations)
            assertEquals(1, observation.counts[IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_ADMITTED])
            assertEquals(emptyList<IntellijReadTermination>(), observation.reasons)
        }
    }

    private companion object {
        const val EXPECTED_STATIC_NODES = 3
    }
}

private class DependencyProofObservation : IntellijReadObservation {
    val counts = mutableMapOf<IntellijReadCounter, Int>()
    val reasons = mutableListOf<IntellijReadTermination>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counts[counter] = counts.getOrDefault(counter, 0) + amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        reasons += reason
    }
}
