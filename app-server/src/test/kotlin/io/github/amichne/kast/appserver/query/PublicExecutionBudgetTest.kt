package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PublicExecutionBudgetTest {
    private val json = Json { encodeDefaults = true }
    private val name = (ProtocolText.parse("Service") as Refinement.Refined).value
    private val budget =
        ExecutionBudgetDocument(
            maxElapsedMillis = (ElapsedTimeLimitMillis.parse(3000) as Refinement.Refined).value,
            maxWorkUnits = (WorkUnitLimit.parse(200000) as Refinement.Refined).value,
            maxResults = (ResultLimit.parse(3) as Refinement.Refined).value,
            maxReturnedBytes = (ReturnedByteLimit.parse(40000) as Refinement.Refined).value,
        )

    @Test
    fun `legacy query retains its rejection of explicit null controls`() {
        val refs =
            (BoundedProtocolList.create(
                    listOf((ProtocolText.parse("exact:v3:EXAMPLE_NOT_ISSUED") as Refinement.Refined).value)
                ) as Refinement.Refined)
                .value
        val invalid = json.encodeToJsonElement(InvalidLegacyBudget(PublicQueryRefs(refs), null as String?))
        assertInstanceOf(Refinement.Rejected::class.java, PublicQueryContract.admit(invalid))
        val valid =
            json.encodeToJsonElement(
                PublicQueryDocument(PublicQueryDocumentType.QUERY, PublicQueryRefs(refs), executionBudget = budget)
            )
        assertInstanceOf(Refinement.Refined::class.java, PublicQueryContract.admit(valid))
    }

    @Test
    fun `every search and query facade retains all four requested allowances`() {
        val refs = (BoundedProtocolList.create(listOf(name)) as Refinement.Refined).value
        val cases =
            listOf(
                PublicToolIdentity.SEARCH_CLASSES to
                    json.encodeToJsonElement(PublicToolSearchClasses(name, null, null, budget)),
                PublicToolIdentity.SEARCH_FUNCTIONS to
                    json.encodeToJsonElement(PublicToolSearchFunctions(name, null, null, budget)),
                PublicToolIdentity.SEARCH_DECLARATIONS to
                    json.encodeToJsonElement(PublicToolSearchDeclarations(name, null, null, null, budget)),
                PublicToolIdentity.QUERY_SYMBOLS to
                    json.encodeToJsonElement(
                        PublicToolQuerySymbols(PublicToolReferenceSource(refs), null, null, null, budget)
                    ),
            )
        for ((identity, document) in cases) {
            val admitted = PublicToolContract.admit(identity, document) as Refinement.Refined
            assertEquals(budget, (admitted.value.canonical as PublicToolCanonical.Query).request.executionBudget)
        }
        val legacy = PublicQueryDocument(PublicQueryDocumentType.QUERY, PublicQueryRefs(refs), executionBudget = budget)
        assertEquals(budget, legacy.toCanonicalQuery().executionBudget)
    }

    @Test
    fun `quoted and nonpositive caller allowances fail public admission`() {
        val invalid =
            listOf(
                json.encodeToJsonElement(InvalidSearch(InvalidBudget("100"))),
                json.encodeToJsonElement(InvalidSearch(InvalidBudget(0))),
                json.encodeToJsonElement(InvalidSearch(InvalidBudget(-1))),
                json.encodeToJsonElement(InvalidSearch(InvalidBudget(1.5))),
            )
        for (document in invalid) assertInstanceOf(
            Refinement.Rejected::class.java,
            PublicToolContract.admit(PublicToolIdentity.SEARCH_CLASSES, document),
        )
    }
}

@Serializable
private data class InvalidSearch<T>(
    @kotlinx.serialization.SerialName("execution_budget") val executionBudget: InvalidBudget<T>,
    @kotlinx.serialization.SerialName("class_name") val className: String = "Service",
    @kotlinx.serialization.SerialName("name_match") val nameMatch: String? = null,
    val scope: String? = null,
)

@Serializable
private data class InvalidBudget<T>(@kotlinx.serialization.SerialName("max_work_units") val maxWorkUnits: T)

@Serializable
private data class InvalidLegacyBudget<T>(
    val from: PublicQuerySource,
    @kotlinx.serialization.SerialName("execution_budget") val executionBudget: T,
    val type: String = "QUERY",
)
