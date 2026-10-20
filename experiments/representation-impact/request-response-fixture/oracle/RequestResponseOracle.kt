package fixture.oracle

enum class SourcePolicy { PRODUCTION_ONLY, PRODUCTION_AND_TEST }

enum class Seed { S1, S2, S3 }

enum class Verdict { CONDITIONAL_PATH, OPAQUE_ARGUMENT, OPAQUE_ORIGIN, FORBIDDEN, EXCLUDED }

enum class Transfer { IDENTITY, PROJECTION, CONSTRUCTION, DERIVATION }

enum class TestedValue { EXECUTE_RESULT, FIRST_OR_NULL_RESULT, ADAPT_RESULT, ECHO_RESULT, SEND_RESULT }

enum class Control {
    NONEMPTY_SUCCESS, EMPTY_SUCCESS, BACKEND_ERROR, CONSTANT_MAPPER,
    TWO_MATCHED_MAPPER_CALLS, SAME_TYPED_FIELDS, STALE_BASIS, WRONG_OWNER, WRONG_CALLABLE,
    SELECTOR_ONLY_RANGE, NAMED_LOCAL_AND_NESTED_EXPRESSION,
}

sealed interface Guard {
    data class Subtype(val value: TestedValue, val qualifiedName: String) : Guard
    data class NonNull(val value: TestedValue) : Guard
}

data class FieldRoute(
    val seedFields: List<String>,
    val destination: String,
    val destinationFields: List<String>,
    val transfers: Set<Transfer>,
    val guards: Set<Guard> = emptySet(),
)

data class OracleRow(
    val id: String,
    val seed: Seed,
    val verdict: Verdict,
    val routes: List<FieldRoute>,
    val policies: Set<SourcePolicy> = SourcePolicy.entries.toSet(),
    val implementation: String? = null,
    val sourceSet: String = "main",
    val obligations: Set<String> = emptySet(),
)

data class ArgumentSite(val callable: String, val parameterName: String, val declaredIndex: Int)

data class VariantOracle(
    val id: String,
    val sourcePath: String,
    val outgoingConstructor: String = "ClientRequest(request.destination)",
    val incomingInvocation: String = "transport.send(wireRequest)",
    val returnFields: List<String> = listOf("link"),
    val outgoingTarget: ArgumentSite = ArgumentSite("Transport.send", "request", 0),
    val guards: Set<Guard> = emptySet(),
    val constructorTarget: ArgumentSite? = null,
    val opaqueFunctionInvocation: String? = null,
)

object RequestResponseOracle {
    const val PACKET_SHA256 = "cc93622878e28b0f0ee0c8ebb874e7a53800a05bf4cdb31a3fe8aa35dda4fcae"
    const val AUDIT_SHA256 = "1c3923270b9088ab6e39dabf496aec829c26e22aeac738cfd7510e65f180c3c9"
    const val SUCCESS = "fixture.outcome.Outcome.Success"
    const val ERROR = "fixture.outcome.Outcome.Error"
    const val PRODUCTION_IMPLEMENTATION = "fixture.client.OffersClientImpl.readOffers"
    const val ECHO_IMPLEMENTATION = "fixture.client.EchoOffersClient.readOffers"

    val success = Guard.Subtype(TestedValue.EXECUTE_RESULT, SUCCESS)
    val error = Guard.Subtype(TestedValue.EXECUTE_RESULT, ERROR)
    val selectedLink = Guard.NonNull(TestedValue.FIRST_OR_NULL_RESULT)

    private fun path(
        source: String,
        destination: String,
        target: String = "",
        transfers: Set<Transfer> = setOf(Transfer.IDENTITY),
        guards: Set<Guard> = emptySet(),
    ) = FieldRoute(source.split('.').filter(String::isNotEmpty), destination,
        target.split('.').filter(String::isNotEmpty), transfers, guards)

    val rows: List<OracleRow> = listOf(
        OracleRow("C1.1", Seed.S1, Verdict.CONDITIONAL_PATH,
            listOf(path("", "OffersProcessorImpl.getOffer/client.readOffers:request[0]"))),
        OracleRow("C1.2", Seed.S1, Verdict.CONDITIONAL_PATH,
            listOf(path("", "OffersClientImpl.readOffers:request[0]")),
            implementation = PRODUCTION_IMPLEMENTATION,
            obligations = setOf("IN_DOMAIN_TARGET_SET_IS_NOT_RUNTIME_TARGET_SELECTION")),
        OracleRow("C1.3", Seed.S1, Verdict.CONDITIONAL_PATH,
            listOf(path("destination", "ClientRequest.toWireRequest/result", "destination",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION)))),
        OracleRow("C1.4", Seed.S1, Verdict.OPAQUE_ARGUMENT,
            listOf(path("destination", "OffersApi.readOffers:body[1]", "destination",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY))),
            implementation = PRODUCTION_IMPLEMENTATION,
            obligations = setOf("OPAQUE_ARGUMENT_ARRIVAL_DOES_NOT_DISCHARGE_QUERY_COMPLETION")),
        OracleRow("C1.5", Seed.S1, Verdict.OPAQUE_ARGUMENT,
            listOf(path("sessionToken", "OffersApi.readOffers:headers[0]", "Authorization",
                setOf(Transfer.PROJECTION, Transfer.DERIVATION))),
            implementation = PRODUCTION_IMPLEMENTATION,
            obligations = setOf("REVIEW_STRING_TEMPLATE_PAIR_AND_MAP_MODELS")),
        OracleRow("C1.N1", Seed.S1, Verdict.FORBIDDEN,
            listOf(path("*", "ClientResponse/result", "links.[*]"),
                path("*", "FeatureResponse/result", "link"),
                path("*", "OffersProcessorImpl.getOffer/return", "Success.value.link")),
            implementation = PRODUCTION_IMPLEMENTATION),
        OracleRow("C1.N2", Seed.S1, Verdict.FORBIDDEN,
            listOf(path("*", "Logger.info:message[0]"), path("*", "Logger.warn:message[0]"))),
        OracleRow("C2.1", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value", "OffersClientImpl.readOffers/map:transform.it", guards = setOf(success)))),
        OracleRow("C2.2", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value.body.links", "WireResponse.toClientResponse/result", "links",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION), setOf(success)))),
        OracleRow("C2.3", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Error.value.debugMessage", "Logger.warn:message[0]", transfers =
                setOf(Transfer.PROJECTION, Transfer.DERIVATION), guards = setOf(error))),
            obligations = setOf("REVIEW_STRING_TEMPLATE_AND_LOGGER_TERMINAL_MODELS")),
        OracleRow("C2.4", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Error.value.code", "WireError.toClientError/result", "code",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION), setOf(error)))),
        OracleRow("C2.5", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value.body.links", "OffersClientImpl.readOffers/return", "Success.value.links",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(success)),
                path("Error.value.code", "OffersClientImpl.readOffers/return", "Error.value.code",
                    setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(error)))),
        OracleRow("C2.6", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value.body.links", "OffersProcessorImpl.getOffer/client.readOffers:result", "Success.value.links",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(success)),
                path("Error.value.code", "OffersProcessorImpl.getOffer/client.readOffers:result", "Error.value.code",
                    setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(error))),
            implementation = PRODUCTION_IMPLEMENTATION),
        OracleRow("C2.7", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value.body.links", "OffersProcessorImpl.getOffer/response", "links",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(success)))),
        OracleRow("C2.8", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value.body.links.[*]", "FeatureResponse/result", "link",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(success, selectedLink))),
            obligations = setOf("ELEMENT_ABSTRACTION_DOES_NOT_SELECT_EVERY_ELEMENT")),
        OracleRow("C2.9", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Success.value.body.links.[*]", "OffersProcessorImpl.getOffer/return", "Success.value.link",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(success, selectedLink)))),
        OracleRow("C2.10", Seed.S2, Verdict.CONDITIONAL_PATH,
            listOf(path("Error.value.code", "OffersProcessorImpl.getOffer/return", "Error.value.reason",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY), setOf(error))),
            obligations = setOf("MATCH_THROWN_HALT_PAYLOAD_TO_CATCH_AND_RECONSTRUCTION")),
        OracleRow("C2.N1", Seed.S2, Verdict.FORBIDDEN,
            listOf(path("*", "OffersApi.readOffers:arguments"), path("*", "WireRequest:arguments"),
                path("*", "HeaderBuilder.build:sessionToken[0]"))),
        OracleRow("C2.N2", Seed.S2, Verdict.FORBIDDEN,
            listOf(path("Error.value.*", "FeatureResponse/result", "link", guards = setOf(error)))),
        OracleRow("C3.1", Seed.S3, Verdict.OPAQUE_ORIGIN,
            listOf(path("", "Call.execute/adapt:result")),
            obligations = setOf("FUNCTION_VALUE_HAS_NO_ADMITTED_SOURCE_PRODUCER")),
        OracleRow("C3.2", Seed.S3, Verdict.CONDITIONAL_PATH,
            listOf(path("", "Call.execute/log:argument[0]"),
                path("Success.value.statusCode", "Logger.info:message[0]", transfers =
                setOf(Transfer.PROJECTION, Transfer.DERIVATION), guards =
                setOf(Guard.Subtype(TestedValue.ADAPT_RESULT, SUCCESS))),
                path("Error.value.code", "Logger.info:message[0]", transfers =
                    setOf(Transfer.PROJECTION, Transfer.DERIVATION), guards =
                    setOf(Guard.Subtype(TestedValue.ADAPT_RESULT, ERROR)))),
            obligations = setOf("MATCH_LOG_CALLBACK_SUPPLY_INVOCATION_AND_RESULT", "REVIEW_LOGGER_TERMINAL_MODEL")),
        OracleRow("C3.3", Seed.S3, Verdict.CONDITIONAL_PATH,
            listOf(path("", "Call.execute/return"), path("", "OffersClientImpl.readOffers/execute:result"))),
        OracleRow("C3.N1", Seed.S3, Verdict.FORBIDDEN,
            listOf(path("*", "OffersApi.readOffers:arguments"))),
        OracleRow("T1.COMBINED", Seed.S1, Verdict.CONDITIONAL_PATH,
            listOf(path("destination", "OffersProcessorImpl.getOffer/return", "Success.value.link",
                setOf(Transfer.PROJECTION, Transfer.CONSTRUCTION, Transfer.IDENTITY),
                setOf(Guard.Subtype(TestedValue.ECHO_RESULT, SUCCESS), selectedLink))),
            policies = setOf(SourcePolicy.PRODUCTION_AND_TEST), implementation = ECHO_IMPLEMENTATION, sourceSet = "test"),
        OracleRow("T1.PRODUCTION", Seed.S1, Verdict.EXCLUDED,
            listOf(path("", ECHO_IMPLEMENTATION)), policies = setOf(SourcePolicy.PRODUCTION_ONLY),
            implementation = ECHO_IMPLEMENTATION, sourceSet = "test",
            obligations = setOf("REPORT_ONLY_AUTHORITATIVELY_KNOWN_EXCLUDED_CANDIDATES")),
    )

    val variants = (0..8).map { number ->
        VariantOracle("V$number", "src/main/kotlin/fixture/variants/v$number/V$number.kt",
            incomingInvocation = if (number == 3) "transport.send(WireRequest(request.destination))" else "transport.send(wireRequest)",
            returnFields = if (number in 4..5) listOf("Success", "value", "link") else listOf("link"),
            outgoingTarget = if (number == 7) ArgumentSite("Transport.adapt", "request", 0) else ArgumentSite("Transport.send", "request", 0),
            guards = if (number in 4..5) setOf(Guard.Subtype(TestedValue.SEND_RESULT, SUCCESS)) else emptySet(),
            constructorTarget = if (number == 8) ArgumentSite("WireRequest.<init>", "destination", 1) else null,
            opaqueFunctionInvocation = if (number == 7) "adapt(request)" else null)
    }

    val m1 = setOf("V0", "V1", "V2", "V3", "V8")
    val m2 = setOf("C1.1", "C1.2", "C1.3", "C1.4", "C2.1", "C2.2", "C2.5", "C2.6", "C2.7", "C2.8", "C2.9", "T1.COMBINED")
    val m3 = rows.filter { it.verdict != Verdict.FORBIDDEN }.map(OracleRow::id).toSet() + variants.map(VariantOracle::id)
    val controls = Control.entries.toSet()
}
