package io.github.amichne.kast.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Independent public-input fixtures for CLI contract consumers. */
internal object PublicQueryInputFixture {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
    }

    fun search(name: String, kinds: List<String>? = null, fields: List<String>? = null): String =
        encode(Source.Search(name, kinds = kinds), fields = fields)

    fun all(fields: List<String>? = null): String = encode(Source.All(), fields = fields)

    fun references(values: List<String>): String = encode(Source.References(values))

    fun unsupportedStep(): String = encode(Source.All(), steps = listOf(Step("check_diagnostics")))

    private fun encode(source: Source, steps: List<Step>? = null, fields: List<String>? = null): String =
        json.encodeToString(Query.serializer(), Query(Run(source, steps, fields)))

    @Serializable private data class Query(val request: Run)

    @Serializable
    private data class Run(
        val source: Source,
        val steps: List<Step>?,
        @SerialName("return_fields") val fields: List<String>?,
        val action: String = "run",
    )

    @Serializable private data class Step(val type: String)

    @Serializable
    private sealed interface Source {
        @Serializable
        @SerialName("search_declarations")
        data class Search(
            @SerialName("declaration_name") val name: String,
            @SerialName("name_match") val matching: String? = null,
            @SerialName("declaration_kinds") val kinds: List<String>? = null,
            val scope: String? = null,
        ) : Source

        @Serializable
        @SerialName("all_declarations")
        data class All(
            @SerialName("declaration_kinds") val kinds: List<String>? = null,
            val scope: String? = null,
        ) : Source

        @Serializable
        @SerialName("symbol_refs")
        data class References(@SerialName("symbol_refs") val values: List<String>) : Source
    }
}
