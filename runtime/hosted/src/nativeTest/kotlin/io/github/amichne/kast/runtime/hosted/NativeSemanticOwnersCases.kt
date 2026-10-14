package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.LocalDeclarationKindDocument
import java.security.MessageDigest
import java.util.Locale

internal data class NativeSemanticOwnersRange(val start: Int, val end: Int)

internal enum class NativeSemanticOwnersTargetShape {
    FUNCTION,
    CONSTRUCTOR,
}

internal enum class NativeSemanticOwnersExecutionKind {
    NAMED_FUNCTION,
    LOCAL_FUNCTION,
    PROPERTY,
    GETTER,
    SETTER,
    CONSTRUCTOR_BODY,
    INIT_BLOCK,
    ANONYMOUS_BODY,
    UNPROVEN,
}

internal enum class NativeSemanticOwnersCompletion {
    COMPLETE,
    QUALIFIED,
    REJECTED,
}

internal enum class NativeSemanticOwnersScan {
    EXHAUSTIVE,
    INCOMPLETE,
    NOT_APPLICABLE,
}

internal enum class NativeSemanticOwnersEffect {
    DEFERRED_NO_CARDINALITY,
    EXACTLY_ONCE,
}

internal data class NativeSemanticOwnersCase(
    val id: String,
    val method: String,
    val resource: String,
    val sha256: String,
    val targetShape: NativeSemanticOwnersTargetShape,
    val targetIdentity: String,
    val supplierIdentity: String,
    val supplierDeclaration: NativeSemanticOwnersRange,
    val sourceEnclosing: NativeSemanticOwnersRange,
    val executionOwner: NativeSemanticOwnersRange,
    val executionKind: NativeSemanticOwnersExecutionKind,
    val supply: NativeSemanticOwnersRange,
    val body: NativeSemanticOwnersRange,
    val occurrence: NativeSemanticOwnersRange,
    val directOuterCalls: Set<NativeSemanticOwnersRange>,
) {
    fun source(): String {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)).use { it.readBytes() }
        val actual =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
        check(actual == sha256) { "Changed semantic owner input for $id: expected $sha256, received $actual" }
        return bytes.toString(Charsets.UTF_8)
    }

    fun expected(): NativeSemanticOwnersSemanticResult =
        NativeSemanticOwnersSemanticResult(
            completion = NativeSemanticOwnersCompletion.COMPLETE,
            scan = NativeSemanticOwnersScan.EXHAUSTIVE,
            targets = setOf(expectedTarget()),
            namedCallers = emptySet(),
            supplierIdentity = supplierIdentity,
            supplierDeclaration = supplierDeclaration,
            supplierSignature = expectedSupplierSignature(),
            executionOwner = executionOwner,
            executionKind = executionKind,
            supply = supply,
            bodies = setOf(body),
            directOuterCalls = directOuterCalls,
            executionRoute = expectedExecutionRoute(),
            effect = NativeSemanticOwnersEffect.DEFERRED_NO_CARDINALITY,
            obligations = emptySet(),
            failures = emptySet(),
            omissions = emptySet(),
            generation = 1L,
            sourceFile = "src/backend-service-clients/Owners.kt",
            sourceSets = setOf("main"),
            directory = "src/backend-service-clients",
            relation = "CALLERS",
        )

    private fun expectedExecutionRoute(): List<NativeSemanticOwnersExecutionStep> {
        var owner: NativeSemanticOwnersExecutionParent =
            NativeSemanticOwnersExecutionParent.Named(supplierIdentity, supplierDeclaration, ENTRY_COMPILER_IDENTITY)
        return directOuterCalls
            .sortedBy { it.start }
            .map { call ->
                val body = NativeSemanticOwnersRange(call.start + 1, call.end - DIRECT_CALL_SUFFIX_LENGTH)
                val identity = "anonymous@src/backend-service-clients/Owners.kt#${body.start}:${body.end}"
                val step =
                    NativeSemanticOwnersExecutionStep(
                        call = call,
                        body = body,
                        bodyIdentity = identity,
                        owner = owner,
                    )
                owner = NativeSemanticOwnersExecutionParent.Anonymous(body, identity)
                step
            }
    }

    private fun expectedTarget(): NativeSemanticOwnersTarget {
        val declaration =
            when (targetShape) {
                NativeSemanticOwnersTargetShape.FUNCTION -> "fun target() {}"
                NativeSemanticOwnersTargetShape.CONSTRUCTOR -> "class Target"
            }
        val start = "package semanticowners\n".length
        return NativeSemanticOwnersTarget(
            identity = targetIdentity,
            shape = targetShape,
            occurrence = occurrence,
            declaration = NativeSemanticOwnersRange(start, start + declaration.length),
            file = "src/backend-service-clients/Owners.kt",
            compilerIdentity =
                when (targetShape) {
                    NativeSemanticOwnersTargetShape.FUNCTION -> FUNCTION_TARGET_COMPILER_IDENTITY
                    NativeSemanticOwnersTargetShape.CONSTRUCTOR -> CLASS_TARGET_COMPILER_IDENTITY
                },
        )
    }

    private fun expectedSupplierSignature(): NativeSemanticOwnersSupplierSignature =
        when (executionKind) {
            NativeSemanticOwnersExecutionKind.LOCAL_FUNCTION -> expectedLocalSignature()
            NativeSemanticOwnersExecutionKind.INIT_BLOCK ->
                NativeSemanticOwnersSupplierSignature.ClassLike(
                    identity = supplierIdentity,
                    compilerIdentity =
                        semanticOwnersCanonicalIdentity(
                            listOf("canonical-signature-v1", "class-like", supplierIdentity)
                        ),
                )
            NativeSemanticOwnersExecutionKind.PROPERTY,
            NativeSemanticOwnersExecutionKind.GETTER,
            NativeSemanticOwnersExecutionKind.SETTER -> expectedPropertySignature()
            NativeSemanticOwnersExecutionKind.NAMED_FUNCTION,
            NativeSemanticOwnersExecutionKind.CONSTRUCTOR_BODY,
            NativeSemanticOwnersExecutionKind.ANONYMOUS_BODY ->
                NativeSemanticOwnersSupplierSignature.Function(
                    identity = supplierIdentity,
                    receiver = NativeSemanticOwnersReceiver.Absent,
                    contextReceiverTypes = emptyList(),
                    valueParameterTypes = emptyList(),
                    typeParameterCount = 0,
                    compilerIdentity =
                        semanticOwnersCanonicalIdentity(
                            listOf(
                                "canonical-signature-v1",
                                "function",
                                supplierIdentity,
                                "receiver-absent",
                                "0",
                                "0",
                                "0",
                            )
                        ),
                )
            NativeSemanticOwnersExecutionKind.UNPROVEN -> error("A required owner cannot be unproven")
        }

    private fun expectedPropertySignature(): NativeSemanticOwnersSupplierSignature.Property {
        val returnType =
            when (executionKind) {
                NativeSemanticOwnersExecutionKind.GETTER -> "kotlin/Any"
                NativeSemanticOwnersExecutionKind.SETTER -> "kotlin/Int"
                else ->
                    when (targetShape) {
                        NativeSemanticOwnersTargetShape.FUNCTION -> "kotlin/Lazy<kotlin/Unit>"
                        NativeSemanticOwnersTargetShape.CONSTRUCTOR -> "kotlin/Lazy<semanticowners/Target>"
                    }
            }
        return NativeSemanticOwnersSupplierSignature.Property(
            identity = supplierIdentity,
            receiver = NativeSemanticOwnersReceiver.Absent,
            contextReceiverTypes = emptyList(),
            returnType = returnType,
            compilerIdentity =
                semanticOwnersCanonicalIdentity(
                    listOf(
                        "canonical-signature-v1",
                        "property-v2",
                        supplierIdentity,
                        "receiver-absent",
                        "0",
                        returnType,
                    )
                ),
        )
    }

    private fun expectedLocalSignature(): NativeSemanticOwnersSupplierSignature.LocalFunction =
        NativeSemanticOwnersSupplierSignature.LocalFunction(
            file = "src/backend-service-clients/Owners.kt",
            kind = LocalDeclarationKindDocument.FUNCTION,
            declaration = supplierDeclaration,
            ownerIdentity = ENTRY_COMPILER_IDENTITY,
            ownerDeclaration = sourceEnclosing,
            lexicalOwners =
                listOf(NativeSemanticOwnersRange(sourceEnclosing.start + "fun entry() ".length, sourceEnclosing.end)),
            receiver = NativeSemanticOwnersReceiver.Absent,
            contextReceiverTypes = emptyList(),
            valueParameterTypes = emptyList(),
            typeParameterCount = 0,
            returnType = "kotlin/Unit",
        )

    fun accepts(actual: NativeSemanticOwnersSemanticResult): Boolean = actual == expected()
}

internal data class NativeSemanticOwnersTarget(
    val identity: String,
    val shape: NativeSemanticOwnersTargetShape,
    val occurrence: NativeSemanticOwnersRange,
    val declaration: NativeSemanticOwnersRange,
    val file: String,
    val compilerIdentity: String,
)

internal data class NativeSemanticOwnersSemanticResult(
    val completion: NativeSemanticOwnersCompletion,
    val scan: NativeSemanticOwnersScan,
    val targets: Set<NativeSemanticOwnersTarget>,
    val namedCallers: Set<String>,
    val supplierIdentity: String,
    val supplierDeclaration: NativeSemanticOwnersRange,
    val supplierSignature: NativeSemanticOwnersSupplierSignature,
    val executionOwner: NativeSemanticOwnersRange,
    val executionKind: NativeSemanticOwnersExecutionKind,
    val supply: NativeSemanticOwnersRange,
    val bodies: Set<NativeSemanticOwnersRange>,
    val directOuterCalls: Set<NativeSemanticOwnersRange>,
    val executionRoute: List<NativeSemanticOwnersExecutionStep>,
    val effect: NativeSemanticOwnersEffect,
    val obligations: Set<String>,
    val failures: Set<String>,
    val omissions: Set<String>,
    val generation: Long,
    val sourceFile: String,
    val sourceSets: Set<String>,
    val directory: String,
    val relation: String,
)

/** Authored source locations and expected relationships do not consume Kast output. */
internal object NativeSemanticOwnersCases {
    val required: List<NativeSemanticOwnersCase> =
        checkNotNull(javaClass.classLoader.getResourceAsStream("semantic-owners/manifest.tsv"))
            .bufferedReader(Charsets.UTF_8)
            .use { reader ->
                val rows = reader.readLines()
                check(rows.first().split("\t") == NativeSemanticOwnersColumn.entries.map { it.header })
                rows.drop(1).map(::parseCase)
            }

    private fun parseCase(row: String): NativeSemanticOwnersCase {
        val fields = row.split("\t")
        check(fields.size == NativeSemanticOwnersColumn.entries.size)
        fun field(column: NativeSemanticOwnersColumn) = fields[column.ordinal]
        val context = field(NativeSemanticOwnersColumn.CONTEXT)
        val execution = span(field(NativeSemanticOwnersColumn.EXECUTION_RANGE))
        val enclosing = span(field(NativeSemanticOwnersColumn.ENCLOSING_RANGE))
        val anonymous = context in setOf("direct-lambda", "direct-anonymous", "depth-two-lambda", "depth-two-anonymous")
        val declaration = if (anonymous || context in setOf("getter", "setter", "init-block")) enclosing else execution
        return NativeSemanticOwnersCase(
            id = field(NativeSemanticOwnersColumn.ID),
            method = field(NativeSemanticOwnersColumn.METHOD),
            resource = field(NativeSemanticOwnersColumn.RESOURCE),
            sha256 = field(NativeSemanticOwnersColumn.SHA256),
            targetShape =
                when (field(NativeSemanticOwnersColumn.TARGET_IDENTITY)) {
                    "semanticowners.target" -> NativeSemanticOwnersTargetShape.FUNCTION
                    "semanticowners.Target" -> NativeSemanticOwnersTargetShape.CONSTRUCTOR
                    else -> error("Unknown semantic owner target: ${field(NativeSemanticOwnersColumn.TARGET_IDENTITY)}")
                },
            targetIdentity = field(NativeSemanticOwnersColumn.TARGET_IDENTITY),
            supplierIdentity =
                if (anonymous) "semanticowners.entry" else field(NativeSemanticOwnersColumn.OWNER_IDENTITY),
            supplierDeclaration = declaration,
            sourceEnclosing = enclosing,
            executionOwner = execution,
            executionKind = executionKind(context),
            supply = span(field(NativeSemanticOwnersColumn.SUPPLY_RANGE)),
            body = span(field(NativeSemanticOwnersColumn.BODY_RANGE)),
            occurrence = span(field(NativeSemanticOwnersColumn.OCCURRENCE_RANGE)),
            directOuterCalls =
                field(NativeSemanticOwnersColumn.OUTER_CALLS).split(",").filter(String::isNotEmpty).map(::span).toSet(),
        )
    }

    private fun executionKind(context: String): NativeSemanticOwnersExecutionKind =
        when (context) {
            "named-function",
            "local-property" -> NativeSemanticOwnersExecutionKind.NAMED_FUNCTION
            "local-function" -> NativeSemanticOwnersExecutionKind.LOCAL_FUNCTION
            "top-property",
            "member-property" -> NativeSemanticOwnersExecutionKind.PROPERTY
            "getter" -> NativeSemanticOwnersExecutionKind.GETTER
            "setter" -> NativeSemanticOwnersExecutionKind.SETTER
            "constructor-body" -> NativeSemanticOwnersExecutionKind.CONSTRUCTOR_BODY
            "init-block" -> NativeSemanticOwnersExecutionKind.INIT_BLOCK
            "direct-lambda",
            "direct-anonymous",
            "depth-two-lambda",
            "depth-two-anonymous" -> NativeSemanticOwnersExecutionKind.ANONYMOUS_BODY
            else -> error("Unknown semantic owner context: $context")
        }

    private fun span(raw: String): NativeSemanticOwnersRange {
        val fields = raw.split(":")
        check(fields.size == 2)
        val start = fields[0].toInt()
        val end = fields[1].toInt()
        check(start >= 0 && end > start)
        return NativeSemanticOwnersRange(start, end)
    }

    fun named(id: String): NativeSemanticOwnersCase = required.single { it.id == id }
}

private enum class NativeSemanticOwnersColumn(val header: String) {
    ID("case_id"),
    METHOD("method"),
    RESOURCE("resource"),
    SHA256("sha256"),
    TARGET_IDENTITY("target_identity"),
    CONTEXT("owner_context"),
    OWNER_IDENTITY("owner_identity"),
    EXECUTION_RANGE("execution_owner_range"),
    ENCLOSING_RANGE("lexical_range"),
    SUPPLY_RANGE("lazy_call_range"),
    BODY_RANGE("body_range"),
    OCCURRENCE_RANGE("target_occurrence_range"),
    OUTER_CALLS("direct_outer_calls"),
    PROPERTY_RANGE("initializer_or_property_range"),
    RELATION("relation"),
    NAMED_CALLERS("named_callers"),
    EFFECT("effect"),
    REQUIRED_STATUS("required_status"),
    BASELINE_STATUS("baseline_status"),
}

internal sealed interface NativeSemanticOwnersReceiver {
    data object Absent : NativeSemanticOwnersReceiver

    data class Present(val type: String) : NativeSemanticOwnersReceiver
}

internal sealed interface NativeSemanticOwnersSupplierSignature {
    data class Function(
        val identity: String,
        val receiver: NativeSemanticOwnersReceiver,
        val contextReceiverTypes: List<String>,
        val valueParameterTypes: List<String>,
        val typeParameterCount: Int,
        val compilerIdentity: String,
    ) : NativeSemanticOwnersSupplierSignature

    data class Property(
        val identity: String,
        val receiver: NativeSemanticOwnersReceiver,
        val contextReceiverTypes: List<String>,
        val returnType: String,
        val compilerIdentity: String,
    ) : NativeSemanticOwnersSupplierSignature

    data class ClassLike(val identity: String, val compilerIdentity: String) : NativeSemanticOwnersSupplierSignature

    data class LocalFunction(
        val file: String,
        val kind: LocalDeclarationKindDocument,
        val declaration: NativeSemanticOwnersRange,
        val ownerIdentity: String,
        val ownerDeclaration: NativeSemanticOwnersRange,
        val lexicalOwners: List<NativeSemanticOwnersRange>,
        val receiver: NativeSemanticOwnersReceiver,
        val contextReceiverTypes: List<String>,
        val valueParameterTypes: List<String>,
        val typeParameterCount: Int,
        val returnType: String,
    ) : NativeSemanticOwnersSupplierSignature
}

/** Source entry() has no receiver, context receiver, value parameter, or type parameter. */
private const val ENTRY_COMPILER_IDENTITY =
    "canonical-signature-sha256-v1|f1629453dbce9cf0d1d3b81e57da2f43d835b361213b6a1b1040ea74f7438785"

private const val FUNCTION_TARGET_COMPILER_IDENTITY =
    "canonical-signature-sha256-v1|9378d4c00c5b96b5fe67cb33a5cc1eddb8136b53696373fdbde8f096785a00f6"

private const val CLASS_TARGET_COMPILER_IDENTITY =
    "canonical-signature-sha256-v1|3962a396f9748c74cf2871a8a5fd28b9c168b482bd5f9b2f6e1d69815b6a1b04"

internal sealed interface NativeSemanticOwnersExecutionParent {
    data class Named(
        val identity: String,
        val declaration: NativeSemanticOwnersRange,
        val compilerIdentity: String,
    ) : NativeSemanticOwnersExecutionParent

    data class Anonymous(val body: NativeSemanticOwnersRange, val sourceIdentity: String) :
        NativeSemanticOwnersExecutionParent
}

internal data class NativeSemanticOwnersExecutionStep(
    val call: NativeSemanticOwnersRange,
    val body: NativeSemanticOwnersRange,
    val bodyIdentity: String,
    val owner: NativeSemanticOwnersExecutionParent,
)

private const val DIRECT_CALL_SUFFIX_LENGTH = 3

/** Source-authored canonical fields use the specified byte-length framing, without a production signature encoder. */
internal fun semanticOwnersCanonicalIdentity(fields: List<String>): String {
    val encoding = fields.joinToString("") { field -> "${field.toByteArray(Charsets.UTF_8).size}:$field" }
    val digest =
        MessageDigest.getInstance("SHA-256").digest(encoding.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }
    return "canonical-signature-sha256-v1|$digest"
}
