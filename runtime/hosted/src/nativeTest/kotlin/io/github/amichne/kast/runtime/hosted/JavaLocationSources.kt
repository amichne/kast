package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryPackageScopeDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import junit.framework.TestCase.assertEquals

internal val javaLocationSource =
    """
    package mixed;
    public class Endpoint {
        public int ignored = 1;
        public Endpoint() {}
        public static int relay(int value) { return value; }
        public static String relay(String value) { return value.trim(); }
        public static Integer relay(Integer value) { return value; }
        public static int fromJava() { return relay(1); }
        public static String otherJava() { return relay("1"); }
        public static Endpoint sameOwner(Endpoint value) { return value; }
        public interface Contract { int convert(int value); }
        public record Pair(int value) {}
        public enum Mode { ACTIVE }
        public void unsupported() { class Local {} }
    }
    """
        .trimIndent()
internal val kotlinLocationSource =
    """
    package mixed
    fun fromKotlin(): Int = Endpoint.relay(2)
    fun otherKotlin(): String = Endpoint.relay("2")
    """
        .trimIndent()

internal val javaRelaySource =
    """
    package mixed;
    public class LegacyRelay {
        public static int relay(int value) { return value; }
        public static String relay(String value) { return value; }
        public static int fromJava() { return relay(1); }
    }
    """
        .trimIndent()

internal const val KOTLIN_RELAY_SOURCE = "package mixed\nfun consume() = LegacyRelay.relay(2)"

internal val javaDiscoverySources =
    mapOf(
        "LegacyRelay.java" to javaRelaySource,
        "Consumers.kt" to KOTLIN_RELAY_SOURCE,
        "other/Other.java" to "package foreign; class Other { static int relay(int value) { return value; } }",
    )

internal suspend fun assertJavaDiscovery(
    request: (String, Int) -> QueryRunRequest.Run,
    symbols: suspend (QueryRunRequest.Run) -> List<QueryResultItemDocument.ExactSymbol>,
    assertUses: suspend (QueryReferenceDocument.ExactSymbol) -> Unit,
) {
    val base = request("LegacyRelay.java", javaRelaySource.indexOf("class LegacyRelay"))
    val owner = symbols(base).single()
    val overloads =
        listOf("relay(int", "relay(String").map { marker ->
            symbols(request("LegacyRelay.java", javaRelaySource.indexOf(marker))).single()
        }
    val other =
        symbols(request("other/Other.java", javaDiscoverySources.getValue("other/Other.java").indexOf("relay(")))
            .single()
    suspend fun named(name: String, kind: QueryDeclarationKindDocument, scope: QueryScopeDocument) =
        symbols(
            base.copy(
                from =
                    QueryFromDocument.Symbols(
                        QueryMatchDocument.Name(packetQueryText(name), SymbolDiscoveryMatchDocument.EXACT_NAME),
                        scope,
                        packetQueryList(listOf(kind)),
                    )
            )
        )
    val global = QueryScopeDocument(packetQueryList(listOf(packetQueryText("main"))), null, null)
    val declared = QueryDirectoryScopeDocument(packetQueryText("src"), QueryContainmentDocument.DIRECT)
    val pkg = QueryPackageScopeDocument(packetQueryText("mixed"), QueryContainmentDocument.DIRECT)
    fun identity(row: QueryResultItemDocument.ExactSymbol) = Triple(row.symbolId, row.signature, row.location)
    for (directory in listOf(null, declared)) {
        val scope = global.copy(directory = directory)
        assertEquals(
            identity(owner),
            identity(named("LegacyRelay", QueryDeclarationKindDocument.CLASS, scope).single()),
        )
        val all = named("relay", QueryDeclarationKindDocument.FUNCTION, scope)
        val expected = if (directory == null) overloads + other else overloads
        assertEquals(expected.map(::identity).toSet(), all.map(::identity).toSet())
        val selected = named("relay", QueryDeclarationKindDocument.FUNCTION, scope.copy(packageName = pkg))
        assertEquals(overloads.size, selected.size)
        assertEquals(overloads.map(::identity).toSet(), selected.map(::identity).toSet())
        assertUses(selected.single { it.signature == overloads.first().signature }.ref)
    }
    val otherSet = global.copy(sourceSets = packetQueryList(listOf(packetQueryText("test"))))
    assertEquals(
        emptyList<QueryResultItemDocument.ExactSymbol>(),
        named("relay", QueryDeclarationKindDocument.FUNCTION, otherSet),
    )
    val outside = global.copy(directory = declared.copy(path = packetQueryText("src/other")))
    assertEquals(
        listOf(identity(other)),
        named("relay", QueryDeclarationKindDocument.FUNCTION, outside).map(::identity),
    )
}

internal suspend fun assertWarmJavaNames(
    initial: QueryRunRequest.Run,
    edit: (String) -> Unit,
    select: suspend (QueryRunRequest.Run) -> List<QueryResultItemDocument.ExactSymbol>,
) {
    val request =
        initial.copy(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(packetQueryText("relay"), SymbolDiscoveryMatchDocument.EXACT_NAME),
                    QueryScopeDocument(packetQueryList(listOf(packetQueryText("main"))), null, null),
                    packetQueryList(listOf(QueryDeclarationKindDocument.FUNCTION)),
                )
        )
    suspend fun assertParameters(expected: List<String>) {
        val parameters =
            select(request).map { row ->
                val signature = row.signature as CompilerSignatureDocument.Function
                assertEquals("mixed.LegacyRelay.relay", signature.qualifiedIdentity.value)
                signature.valueParameters.values.single().value
            }
        assertEquals(expected.sorted(), parameters.sorted())
    }
    val initialParameters = listOf("kotlin/Int", "kotlin/String!")
    assertParameters(initialParameters)
    val added =
        javaRelaySource.replace(
            "public static int fromJava",
            "public static long relay(long value) { return value; } public static int fromJava",
        )
    edit(added)
    assertParameters(initialParameters + "kotlin/Long")
    edit(javaRelaySource)
    assertParameters(initialParameters)
    edit(javaRelaySource.replace("int relay(int", "int renamed(int").replace("relay(1)", "renamed(1)"))
    assertParameters(listOf("kotlin/String!"))
}

/** Each declaration exists in `Endpoint.java`, and no supported kind can select it. Both exact-name routes meet it. */
internal fun unsupportedJavaByName() =
    listOf(null, "src").flatMap { directory ->
        listOf(
            javaByName("Pair", QueryDeclarationKindDocument.CLASS, directory),
            javaByName("ignored", QueryDeclarationKindDocument.PROPERTY, directory),
            javaByName("ACTIVE", QueryDeclarationKindDocument.CLASS, directory),
        )
    }

/** A field is no function, an enum constant is no property, and another directory or package holds neither. */
internal fun excludedJavaByName() =
    listOf(
        javaByName("ignored", QueryDeclarationKindDocument.FUNCTION, null),
        javaByName("ACTIVE", QueryDeclarationKindDocument.PROPERTY, null),
        javaByName("ignored", QueryDeclarationKindDocument.PROPERTY, "src/other"),
        javaByName("ignored", QueryDeclarationKindDocument.PROPERTY, null).let { source ->
            val foreign = QueryPackageScopeDocument(packetQueryText("foreign"), QueryContainmentDocument.DIRECT)
            source.copy(scope = source.scope.copy(packageName = foreign))
        },
    )

private fun javaByName(name: String, kind: QueryDeclarationKindDocument, directory: String?) =
    QueryFromDocument.Symbols(
        QueryMatchDocument.Name(packetQueryText(name), SymbolDiscoveryMatchDocument.EXACT_NAME),
        QueryScopeDocument(
            packetQueryList(listOf(packetQueryText("main"))),
            directory?.let { QueryDirectoryScopeDocument(packetQueryText(it), QueryContainmentDocument.DIRECT) },
            null,
        ),
        packetQueryList(listOf(kind)),
    )
