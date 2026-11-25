package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPackageScopeDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import kotlinx.coroutines.runBlocking

/** A complete inventory has every declaration of its kinds. A Java declaration it cannot select leaves it unproven. */
class NativeJavaInventoryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testInventoryIncludesJavaClassesAndMethods() = runBlocking {
        val fixture = prepare()
        assertEquals(
            Rows.Complete(setOf("Bean", "Inner", "Contract", "getLabel", "inner", "convert")),
            fixture.rows(inventory("src/legacy", CLASS, FUNCTION)),
        )
        assertEquals(Rows.Complete(setOf("consume", "Consumer")), fixture.rows(inventory("src/app", CLASS, FUNCTION)))
    }

    /** The boundary recipe must find a Kotlin use of a Java declaration. */
    fun testBoundaryFromAJavaInventoryFindsKotlinUses() = runBlocking {
        val fixture = prepare()
        val destinations =
            QueryExpansionScopeDocument.Sources(
                sourceSets = packetQueryList(listOf(packetQueryText("main"))),
                directory = directory("src/app"),
                sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
                generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
            )
        val references = QueryStepDocument.Related(RelationKindDocument.REFERENCES, destinations)
        val boundary =
            inventory("src/legacy", CLASS)
                .copy(steps = packetQueryList(listOf(references)), output = QueryOutputDocument.Occurrences)
        val outcome = fixture.execute(boundary)
        assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
        val targets =
            (outcome as OperationOutcome.Complete).evidence.payload.items.values.map {
                (it as QueryResultItemDocument.ReferenceOccurrence).occurrence.target.name.value
            }
        assertEquals(setOf("Bean"), targets.toSet())
    }

    fun testUnselectableJavaDeclarationsLeaveTheirKindsUnproven() = runBlocking {
        val fixture = prepare()
        assertEquals(Rows.Unproven, fixture.rows(inventory("src/legacy", PROPERTY)))
        for (kind in listOf(CLASS, FUNCTION)) {
            assertEquals("$kind", Rows.Unproven, fixture.rows(inventory("src/special", kind)))
        }
    }

    /** Kind, directory and package exclude a Java declaration before it can qualify the inventory. */
    fun testExcludedJavaDeclarationsKeepTheInventoryComplete() = runBlocking {
        val fixture = prepare()
        assertEquals(Rows.Complete(setOf("seen")), fixture.rows(inventory("src/app", PROPERTY)))
        assertEquals(Rows.Complete(emptySet()), fixture.rows(inventory("src/legacy", TYPE_ALIAS)))
        assertEquals(Rows.Complete(emptySet()), fixture.rows(inventory("src/special", TYPE_ALIAS)))
        val foreign = QueryPackageScopeDocument(packetQueryText("absent"), QueryContainmentDocument.DIRECT)
        assertEquals(Rows.Complete(emptySet()), fixture.rows(inventory("src/special", CLASS, packageName = foreign)))
    }

    private sealed interface Rows {
        data class Complete(val names: Set<String>) : Rows

        data object Unproven : Rows
    }

    private suspend fun NativeScopedTraceFixture.rows(request: QueryRunRequest.Run): Rows =
        when (val outcome = execute(request)) {
            is OperationOutcome.Complete ->
                Rows.Complete(
                    outcome.evidence.payload.items.values
                        .map { checkNotNull((it as QueryResultItemDocument.ExactSymbol).name).value }
                        .toSet()
                )
            is OperationOutcome.Rejected -> {
                val coverage = (outcome.reason as QueryRunRejection.CompletionUnproven).originalCoverage
                assertEquals(
                    listOf(QueryLimitationDocument.DISCOVERY_INCOMPLETE),
                    (coverage as QueryCompletionCoverageDocument.Qualified).limitations.values,
                )
                Rows.Unproven
            }
            else -> error("Inventory returned $outcome")
        }

    private suspend fun NativeScopedTraceFixture.execute(request: QueryRunRequest.Run) =
        protocol().execute(request = request, lease = lease, budget = NativePacketQueryServices.budget, policy = policy)

    private fun prepare(): NativeScopedTraceFixture {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("java-inventory-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativeSourceContextFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(SOURCES)
            .first
    }
}

private val CLASS = QueryDeclarationKindDocument.CLASS
private val FUNCTION = QueryDeclarationKindDocument.FUNCTION
private val PROPERTY = QueryDeclarationKindDocument.PROPERTY
private val TYPE_ALIAS = QueryDeclarationKindDocument.TYPE_ALIAS

private val SOURCES =
    mapOf(
        "legacy/Bean.java" to
            """
            package mixed;
            public class Bean {
                private String label = "";
                public Bean() {}
                /** Returns the label. */
                public String getLabel() { int local = 1; return label; }
                public static class Inner { void inner() {} }
                public interface Contract { int convert(int value); }
            }
            """
                .trimIndent(),
        "special/Special.java" to
            """
            package special;
            public enum Special { FIRST; void act() {} }
            """
                .trimIndent(),
        "app/Consumer.kt" to
            """
            package app
            class Consumer(val seen: Int)
            fun consume(bean: mixed.Bean) = bean
            """
                .trimIndent(),
    )

private val policy =
    QueryInvocationPolicy(
        previewRows = NativeScopedTraceFixture.resources.resultLimit,
        previewBytesLimit = NativePacketQueryServices.budget.returnedBytes,
        retainedBytes = NativePacketQueryServices.budget.returnedBytes,
        previewBytes = CanonicalQueryCliDocuments::previewBytes,
    )

private fun directory(path: String) =
    QueryDirectoryScopeDocument(packetQueryText(path), QueryContainmentDocument.DESCENDANTS)

private fun inventory(
    path: String,
    vararg kinds: QueryDeclarationKindDocument,
    packageName: QueryPackageScopeDocument? = null,
) =
    QueryRunRequest.Run(
        from =
            QueryFromDocument.Symbols(
                QueryMatchDocument.All,
                QueryScopeDocument(packetQueryList(listOf(packetQueryText("main"))), directory(path), packageName),
                packetQueryList(kinds.toList()),
            ),
        steps = packetQueryList(emptyList()),
        output = QueryOutputDocument.Symbols(packetQueryList(listOf(QuerySymbolFieldDocument.NAME))),
        execution =
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
    )
