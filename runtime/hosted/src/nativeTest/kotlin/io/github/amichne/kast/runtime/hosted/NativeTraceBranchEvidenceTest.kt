package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryBranch
import io.github.amichne.kast.query.contract.QueryBranchAccounting
import io.github.amichne.kast.query.contract.QueryBranchObservation
import io.github.amichne.kast.query.contract.QueryBranchOutcome
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Native canonical ingress and CLI output, not an installed MCP session or a performance benchmark. */
class NativeTraceBranchEvidenceTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testSmallEquivalentCapturesMemberAndRelationReceipts() = runBlocking {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture =
                NativeSemanticOwnersFixture(project, module, getOrCreateProjectBaseDir())
                    .prepare(SMALL_SOURCE, ::registerTestProjectJdk)
        }
        val path = "${NativeScopedTraceFixture.SOURCE_DIRECTORY}/Owners.kt"
        val offset = SMALL_SOURCE.indexOf("Forwarder/*</seed>*/")
        recordSource(
            label = "small",
            path = path,
            source = SMALL_SOURCE,
            seeds = listOf(NativeTraceSeed(offset, offset + "Forwarder".length)),
        )
        recordSource(
            label = "small",
            path = "src/unrelated/Outside.kt",
            source = "package unrelated\nclass Target\nfun target() {}\n",
            seeds = emptyList(),
        )
        val domain =
            CrossModuleCallbackRequests.domain(CrossModuleCallbackUnit.CLIENT)
                .copy(
                    directory =
                        QueryDirectoryScopeDocument(
                            packetQueryText(NativeScopedTraceFixture.SOURCE_DIRECTORY),
                            QueryContainmentDocument.DESCENDANTS,
                        )
                )
        val receipts =
            repeatTrace(label = "small", fixture = fixture, request = request(path, offset, domain), repetitions = 1)
        assertTrue(receipts.any { it.branch == QueryBranch.TRACE_MEMBERS })
        assertTrue(receipts.any { it.branch == QueryBranch.TRACE_MEMBER_REFINEMENT })
    }

    fun testThreeModuleFreshFixtureAAndTwoWarmRepeats() {
        assertMultiModule("multi-A")
    }

    fun testThreeModuleFreshFixtureBAndTwoWarmRepeats() {
        assertMultiModule("multi-B")
    }

    private fun assertMultiModule(label: String) = runBlocking {
        val supply = CrossModuleCallbackSupply.NAMED
        val fixture =
            NativeCrossModuleCallbackFixture(project, getOrCreateProjectBaseDir(), module)
                .prepare(supply, ::registerTestProjectJdk)
        CrossModuleCallbackUnit.entries.forEach { unit ->
            val text = unit.source(supply)
            val seeds =
                if (unit == CrossModuleCallbackUnit.SIGNAL) {
                    val offset = text.indexOf("pulse")
                    listOf(NativeTraceSeed(offset, offset + "pulse".length))
                } else emptyList()
            recordSource(label = label, path = "${unit.directory}/${unit.fileName}", source = text, seeds = seeds)
        }
        val discovery = CrossModuleCallbackRequests.discover(CrossModuleCallbackUnit.SIGNAL, "pulse", supply)
        repeatTrace(
            label = label,
            fixture = fixture,
            request =
                discovery.copy(
                    steps = packetQueryList(listOf(QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace)))
                ),
            repetitions = MULTI_REPETITIONS,
        )
    }

    private suspend fun repeatTrace(
        label: String,
        fixture: NativeScopedTraceFixture,
        request: QueryRunRequest.Run,
        repetitions: Int,
    ): List<QueryBranchAccounting> {
        val receipts = mutableListOf<QueryBranchAccounting>()
        val documents = mutableListOf<HostedQueryBranchDocument>()
        val hostedObservation = hostedQueryBranchObservation(HostedReadTraceObservation.Unobserved, documents::add)
        val protocol =
            fixture.protocol(
                QueryBranchObservation { accounting ->
                    receipts.add(accounting)
                    hostedObservation.observe(accounting)
                }
            )
        val encoded = json.encodeToString(QueryRunRequest.serializer(), request)
        println("TRACE_INPUT label=$label $encoded")
        val results =
            (0 until repetitions).map { run ->
                receipts.clear()
                documents.clear()
                val admitted = json.decodeFromString(QueryRunRequest.serializer(), encoded)
                val started = System.nanoTime()
                val outcome =
                    protocol.execute(
                        request = admitted,
                        lease = fixture.lease,
                        budget = CrossModuleCallbackRequests.budget,
                        policy = CrossModuleCallbackRequests.policy,
                    )
                val elapsed = System.nanoTime() - started
                val projected = CanonicalQueryCliDocuments.project(outcome)
                recordRun(
                    label = label,
                    run = run,
                    elapsed = elapsed,
                    receipts = receipts.zip(documents),
                    projected = projected,
                )
                assertTrue("Native TRACE did not complete: $outcome", outcome is OperationOutcome.Complete)
                val result = (outcome as OperationOutcome.Complete).evidence.payload
                val names = assertResult(label, result, projected)
                println("TRACE_NATIVE_COUNTERS label=$label run=$run cumulative=${fixture.observation.snapshot()}")
                assertTrue("No native branch receipt was emitted", receipts.isNotEmpty())
                assertTrue(receipts.all { it.outcome == QueryBranchOutcome.COMPLETE && it.limitations.isEmpty() })
                assertEquals(receipts.size, documents.size)
                assertTrue(receipts.sumOf { it.elapsed.inWholeNanoseconds } <= elapsed)
                assertTrue(fixture.observation.unexpectedFailures.isEmpty())
                names
            }
        assertEquals("Repeated TRACE changed its semantic names", 1, results.toSet().size)
        return receipts.toList()
    }

    private fun assertResult(
        label: String,
        result: QueryRunResult,
        projected: ProjectedOperationOutcome,
    ): Set<String> {
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
        assertTrue(projected is ProjectedOperationOutcome.Complete)
        val output =
            json.parseToJsonElement((projected as ProjectedOperationOutcome.Complete).document.value).jsonObject
        assertEquals("complete", output.getValue("status").jsonPrimitive.content)
        assertEquals(result.items.values.size, output.getValue("items").jsonArray.size)
        val names =
            result.items.values
                .map {
                    semanticOwnersIdentity(checkNotNull((it as QueryResultItemDocument.ExactSymbol).signature))
                }
                .toSet()
        if (label == "small")
            assertEquals(
                setOf(
                    "equivalent.Forwarder",
                    "equivalent.Forwarder.forward",
                    "equivalent.forwardExtension",
                    "equivalent.entry",
                ),
                names,
            )
        else {
            assertEquals(setOf("signal.pulse", "client.supply"), names)
            assertNamedCallback(result)
        }
        return names
    }

    private fun assertNamedCallback(result: QueryRunResult) {
        val reference =
            result.relationObservations.values
                .flatMap { it.callableObservations.values }
                .map { it.target }
                .filterIsInstance<QueryCallableTargetDocument.NamedReference>()
                .single()
        assertEquals(
            "signal.pulse",
            semanticOwnersIdentity(reference.reference.target.compilerTarget.compilerEvidence.signature),
        )
        val flow = reference.reference.flow as QueryNamedCallbackReferenceFlowDocument.Supplied
        assertEquals(
            "relay.forward",
            semanticOwnersIdentity(flow.binding.callable.compilerTarget.compilerEvidence.signature),
        )
        val invocation = flow.invocations.values.single()
        val owner = invocation.owner as QueryCallbackBodyDocument.Named
        assertEquals("relay.deliver", semanticOwnersIdentity(owner.callable.compilerTarget.compilerEvidence.signature))
        val forwarding = flow.forwarding as QueryCallbackForwardingEvidenceDocument.ExhaustedGraph
        assertEquals(1, forwarding.forwardings.values.size)
    }

    private fun recordRun(
        label: String,
        run: Int,
        elapsed: Long,
        receipts: List<Pair<QueryBranchAccounting, HostedQueryBranchDocument>>,
        projected: ProjectedOperationOutcome,
    ) {
        receipts.forEach { (receipt, document) ->
            println(
                "TRACE_RECEIPT " +
                    json.encodeToString(
                        NativeTraceReceipt(
                            label = label,
                            run = run,
                            sourceFile = receipt.source.file.stableValue,
                            sourceStart = receipt.source.range.startInclusive,
                            sourceEnd = receipt.source.range.endExclusive,
                            compilerIdentity = receipt.source.compilerIdentity.value,
                            elapsedNanos = receipt.elapsed.inWholeNanoseconds,
                            branch = document,
                        )
                    )
            )
        }
        println(
            "TRACE_SAMPLE " +
                json.encodeToString(
                    NativeTraceSample(
                        label = label,
                        run = run,
                        elapsedNanos = elapsed,
                        branchReads = receipts.size,
                        branchChargedWorkUnits = receipts.sumOf { it.first.chargedWorkUnits.value },
                        branchElapsedNanos = receipts.sumOf { it.first.elapsed.inWholeNanoseconds },
                    )
                )
        )
        val output =
            when (projected) {
                is ProjectedOperationOutcome.Complete -> projected.document
                is ProjectedOperationOutcome.Qualified -> projected.document
                is ProjectedOperationOutcome.Rejected -> projected.document
            }
        println("TRACE_OUTPUT label=$label run=$run ${output.value}")
    }

    private fun request(path: String, offset: Int, domain: QueryExpansionScopeDocument) =
        CrossModuleCallbackRequests.discover(CrossModuleCallbackUnit.SIGNAL, "pulse", CrossModuleCallbackSupply.NAMED)
            .copy(
                from = QueryFromDocument.Location(packetQueryText(path), ProtocolOffset.parse(offset).traceRefined()),
                steps = packetQueryList(listOf(QueryStepDocument.Trace(domain))),
            )

    private fun recordSource(label: String, path: String, source: String, seeds: List<NativeTraceSeed>) {
        val bytes = Files.readAllBytes(getOrCreateProjectBaseDir().toNioPath().resolve(path))
        assertEquals("The indexed fixture source must match its frozen input", source, bytes.toString(Charsets.UTF_8))
        val digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        println(
            "TRACE_SOURCE " +
                json.encodeToString(
                    NativeTraceSource(label = label, path = path, sha256 = digest, seeds = seeds, source = source)
                )
        )
    }

    private companion object {
        val json = Json { encodeDefaults = true }
        const val MULTI_REPETITIONS = 3
        const val SMALL_SOURCE =
            "package equivalent\n" +
                "// Minimal TRACE equivalent, not the unavailable enterprise Probe.kt.\n" +
                "class /*<seed>*/Forwarder/*</seed>*/ { fun forward() {} }\n" +
                "fun forwardExtension(forwarder: Forwarder) { forwarder.forward() }\n" +
                "fun entry() { forwardExtension(Forwarder()) }\n"
    }
}

@Serializable
private data class NativeTraceReceipt(
    val label: String,
    val run: Int,
    val sourceFile: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val compilerIdentity: String,
    val elapsedNanos: Long,
    val branch: HostedQueryBranchDocument,
)

@Serializable
private data class NativeTraceSample(
    val label: String,
    val run: Int,
    val elapsedNanos: Long,
    val branchReads: Int,
    val branchChargedWorkUnits: Long,
    val branchElapsedNanos: Long,
)

@Serializable
private data class NativeTraceSource(
    val label: String,
    val path: String,
    val sha256: String,
    val seeds: List<NativeTraceSeed>,
    val source: String,
)

@Serializable private data class NativeTraceSeed(val startInclusive: Int, val endExclusive: Int)
