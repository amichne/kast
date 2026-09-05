package io.github.amichne.kast.distribution.contract.bootstrap

import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Closed installed-workspace failures that may terminate a semantic-runtime bootstrap. */
@Serializable(with = SemanticRuntimeBootstrapFailureSerializer::class)
enum class SemanticRuntimeBootstrapFailure(
    val wireName: String,
) {
    PROJECT_STORE_OVERLAPS_WORKSPACE("project-store-overlaps-workspace"),

    PROJECT_STORE_CREATION_FAILED("project-store-creation-failed"),

    PROJECT_STORE_IDENTITY_REJECTED("project-store-identity-rejected"),

    PROJECT_STORE_EXCLUSION_DISCOVERY_FAILED("project-store-exclusion-discovery-failed"),

    PROJECT_STORE_CONFIGURATION_WRITE_FAILED("project-store-configuration-write-failed"),

    INDEX_BOOTSTRAP_MODULE_UNAVAILABLE("index-bootstrap-module-unavailable"),

    INDEX_BOOTSTRAP_EXCLUSION_POLICY_MISMATCH("index-bootstrap-exclusion-policy-mismatch"),

    INDEX_BOOTSTRAP_CONTENT_ROOT_MISMATCH("index-bootstrap-content-root-mismatch"),

    INDEX_BOOTSTRAP_EXCLUSION_ROOTS_MISMATCH("index-bootstrap-exclusion-roots-mismatch"),

    INDEX_BOOTSTRAP_PLATFORM_OBSERVATION_FAILED("index-bootstrap-platform-observation-failed"),

    INDEX_BOOTSTRAP_RETIREMENT_IDENTITY_LOST("index-bootstrap-retirement-identity-lost"),

    INDEX_BOOTSTRAP_RETIREMENT_FAILED("index-bootstrap-retirement-failed"),

    INDEX_BOOTSTRAP_IMPORTED_MODULES_UNAVAILABLE(
        "index-bootstrap-imported-modules-unavailable",
    ),

    INDEX_BOOTSTRAP_EXCLUSION_ROOT_UNAVAILABLE("index-bootstrap-exclusion-root-unavailable"),

    INDEX_BOOTSTRAP_EXCLUSION_NOT_PRESERVED("index-bootstrap-exclusion-not-preserved"),

    INDEX_BOOTSTRAP_SOURCE_ROOT_NOT_ADMITTED("index-bootstrap-source-root-not-admitted"),

    PROJECT_OPEN_FAILED("project-open-failed"),

    STARTUP_FAILED("startup-failed"),

    TRANSPORT_ACTIVATION_FAILED("transport-activation-failed"),

    RUNTIME_ASSEMBLY_FAILED("runtime-assembly-failed"),

    CACHE_STATE_PUBLICATION_FAILED("cache-state-publication-failed"),

    GRADLE_JVM_UNAVAILABLE("gradle-jvm-unavailable"),

    PROJECT_JVM_UNAVAILABLE("project-jvm-unavailable"),

    PLATFORM_LINKAGE_INVALID("platform-linkage-invalid"),

    GRADLE_IMPORT_FAILED("gradle-import-failed"),

    @SerialName("gradle-tooling-payload-incompatible")
    GRADLE_TOOLING_PAYLOAD_INCOMPATIBLE("gradle-tooling-payload-incompatible"),

    @SerialName("gradle-initialization-script-unavailable")
    GRADLE_INIT_SCRIPT_UNAVAILABLE("gradle-initialization-script-unavailable"),

    GRADLE_PROJECT_POLICY_INVALID("gradle-project-policy-invalid"),

    GRADLE_JVM_CONFIGURATION_INVALID("gradle-jvm-configuration-invalid"),

    GRADLE_IMPORT_TIMED_OUT("gradle-import-timed-out"),

    INDEXING_INTERRUPTED("indexing-interrupted"),

    MODEL_UNAVAILABLE("model-unavailable"),

    MODEL_ROOT_UNAVAILABLE("model-root-unavailable"),

    MODEL_EXTERNAL_PROJECT_UNAVAILABLE("model-external-project-unavailable"),

    MODEL_EXTERNAL_PROJECT_INCOMPLETE("model-external-project-incomplete"),

    MODEL_SOURCE_ROOTS_UNAVAILABLE("model-source-roots-unavailable"),

    MODEL_SOURCE_STATE_UNAVAILABLE("model-source-state-unavailable"),

    MODEL_SEMANTIC_INPUT_INCOMPLETE("model-semantic-input-incomplete"),

    MODEL_SEMANTIC_PROJECT_PATH_INVALID("model-semantic-project-path-invalid"),

    MODEL_SEMANTIC_SOURCE_ROOT_INVALID("model-semantic-source-root-invalid"),

    MODEL_SEMANTIC_MODULE_INVALID("model-semantic-module-invalid"),

    MODEL_STATE_IDENTITY_REJECTED("model-state-identity-rejected"),
}

object SemanticRuntimeBootstrapFailureSerializer :
    KSerializer<SemanticRuntimeBootstrapFailure> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "SemanticRuntimeBootstrapFailure",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: SemanticRuntimeBootstrapFailure) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): SemanticRuntimeBootstrapFailure {
        val wireName = decoder.decodeString()
        return SemanticRuntimeBootstrapFailure.entries.singleOrNull { it.wireName == wireName }
            ?: throw SerializationException("unsupported bootstrap failure")
    }
}

/** Versioned semantic-runtime bootstrap state exchanged across the sidecar process boundary. */
@Serializable
@JvmInline
value class SemanticRuntimeBootstrapAttemptId private constructor(
    val value: String,
) {
    init {
        require(CANONICAL_UUID.matches(value))
    }

    companion object {
        /** Refines one canonical UUID text into a process-bound bootstrap attempt identity. */
        fun admit(raw: String): Refinement<
            SemanticRuntimeBootstrapAttemptId,
            SemanticRuntimeBootstrapAttemptIdFailure,
            > = if (CANONICAL_UUID.matches(raw)) {
            Refinement.Refined(SemanticRuntimeBootstrapAttemptId(raw))
        } else {
            Refinement.Rejected(SemanticRuntimeBootstrapAttemptIdFailure.INVALID_IDENTITY)
        }

        private val CANONICAL_UUID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

enum class SemanticRuntimeBootstrapAttemptIdFailure { INVALID_IDENTITY }

@Serializable
sealed interface SemanticRuntimeBootstrapState {
    val attemptId: SemanticRuntimeBootstrapAttemptId

    @Serializable
    @SerialName("starting")
    data class Starting(
        override val attemptId: SemanticRuntimeBootstrapAttemptId,
        val phase: SemanticRuntimeBootstrapPhase,
        val gradleJvm: GradleJvmSelectionObservation = GradleJvmSelectionObservation.Unobserved,
    ) : SemanticRuntimeBootstrapState {
        constructor(attemptId: SemanticRuntimeBootstrapAttemptId) :
            this(attemptId, SemanticRuntimeBootstrapPhase.DISCOVERING_RUNTIME)
    }

    @Serializable
    @SerialName("ready")
    data class Ready(
        override val attemptId: SemanticRuntimeBootstrapAttemptId,
        val gradleJvm: GradleJvmSelectionObservation = GradleJvmSelectionObservation.Unobserved,
    ) : SemanticRuntimeBootstrapState

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        override val attemptId: SemanticRuntimeBootstrapAttemptId,
        val failure: SemanticRuntimeBootstrapFailure,
        val phase: SemanticRuntimeBootstrapPhase,
        val gradleJvm: GradleJvmSelectionObservation = GradleJvmSelectionObservation.Unobserved,
    ) : SemanticRuntimeBootstrapState {
        constructor(attemptId: SemanticRuntimeBootstrapAttemptId, failure: SemanticRuntimeBootstrapFailure) :
            this(attemptId, failure, SemanticRuntimeBootstrapPhase.DISCOVERING_RUNTIME)
    }
}

enum class SemanticRuntimeBootstrapDocumentFailure {
    MALFORMED_DOCUMENT,
    UNSUPPORTED_SCHEMA,
}

@Serializable
private data class SemanticRuntimeBootstrapDocument(
    val schemaVersion: Int,
    val bootstrap: SemanticRuntimeBootstrapState,
)

/** Canonical JSON codec for the exact bootstrap-state file shared by control and sidecar. */
object SemanticRuntimeBootstrapCodec {
    private val json = Json {
        classDiscriminator = "state"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /** Boundary projection permitted only at an admitted bootstrap-state file writer. */
    fun encode(state: SemanticRuntimeBootstrapState): String =
        json.encodeToString(
            SemanticRuntimeBootstrapDocument.serializer(),
            SemanticRuntimeBootstrapDocument(BOOTSTRAP_SCHEMA_VERSION, state),
        )

    /**
     * Proof transition: `String -> Refinement<SemanticRuntimeBootstrapState,
     * SemanticRuntimeBootstrapDocumentFailure>`.
     */
    fun decode(document: String): Refinement<
        SemanticRuntimeBootstrapState,
        SemanticRuntimeBootstrapDocumentFailure,
        > {
        val schemaVersion = try {
            val envelope = json.parseToJsonElement(document) as? JsonObject
                ?: return malformedDocument()
            envelope["schemaVersion"]?.jsonPrimitive?.intOrNull
                ?: return malformedDocument()
        } catch (_: SerializationException) {
            return malformedDocument()
        } catch (_: IllegalArgumentException) {
            return malformedDocument()
        }
        if (schemaVersion != BOOTSTRAP_SCHEMA_VERSION) {
            return Refinement.Rejected(
                SemanticRuntimeBootstrapDocumentFailure.UNSUPPORTED_SCHEMA,
            )
        }
        return try {
            val decoded = json.decodeFromString(
                SemanticRuntimeBootstrapDocument.serializer(),
                document,
            )
            Refinement.Refined(decoded.bootstrap)
        } catch (_: SerializationException) {
            malformedDocument()
        } catch (_: IllegalArgumentException) {
            malformedDocument()
        }
    }

    private fun malformedDocument(): Refinement.Rejected<
        SemanticRuntimeBootstrapDocumentFailure,
        > = Refinement.Rejected(SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT)

    private const val BOOTSTRAP_SCHEMA_VERSION = 2
}

const val SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME = "bootstrap-state"
