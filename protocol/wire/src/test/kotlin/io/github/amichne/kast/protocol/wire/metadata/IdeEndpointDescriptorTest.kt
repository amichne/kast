package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointDescriptorTest {
    @Test
    fun `the exact v2 descriptor retains every refined endpoint authority`() {
        val descriptor = IdeEndpointDescriptorV2.create(
            fixtureEndpointCandidate(),
            fixtureEndpointPolicy(),
        ).admittedDescriptor()

        assertEquals(IdeEndpointSchema.V2, descriptor.schema)
        assertEquals("/workspace/kast", descriptor.canonicalRoot.value)
        assertEquals(IdeEndpointHostKind.IDE_PROJECT, descriptor.hostKind)
        assertEquals(1L, descriptor.processId.value)
        assertEquals(FIXTURE_IDE_BUILD, descriptor.compatibility.ideBuild.value)
        assertEquals(
            FIXTURE_KOTLIN_PLUGIN_BUILD,
            descriptor.compatibility.kotlinPluginBuild.value,
        )
        assertEquals(FIXTURE_KAST_PLUGIN_VERSION, descriptor.compatibility.kastPluginVersion.value)
        assertEquals(
            FIXTURE_RUNTIME_PROTOCOL,
            descriptor.compatibility.runtimeProtocolIdentity.value,
        )
        assertEquals(
            FIXTURE_REGISTRY_DIGEST,
            descriptor.compatibility.operationRegistryDigest.value,
        )
        assertEquals(FIXTURE_WIRE_DIGEST, descriptor.compatibility.wireSchemaDigest.value)
        assertEquals("/tmp/kast-ide.sock", descriptor.socketPath.value)
        assertEquals(IdeEndpointFraming.LENGTH_PREFIXED_JSON_V1, descriptor.framing)
        assertEquals(0L, descriptor.runtimeEpoch.value)
        assertEquals(IdeHostCapability.entries, descriptor.compatibility.capabilities.capabilities)
    }

    @Test
    fun `canonical encoding round trips to the same descriptor bytes`() {
        val policy = fixtureEndpointPolicy()
        val encoded = IdeEndpointDescriptorV2.create(
            fixtureEndpointCandidate(),
            policy,
        ).admittedDescriptor().encode()
        val decoded = IdeEndpointDescriptorV2.admit(encoded.document, policy).admittedDescriptor()

        assertEquals(encoded.document, decoded.encode().document)
    }

    @Test
    fun `the generated KVP-013 report is the canonical admitted descriptor`() {
        val reportPath = System.getProperty(REPORT_PATH_PROPERTY)
            ?.let(Path::of)
            ?: fail("missing generated KVP-013 report path")
        val report = Files.readString(reportPath)
        val descriptor = IdeEndpointDescriptorV2.admit(
            report,
            fixtureEndpointPolicy(),
        ).admittedDescriptor()

        assertEquals(IdeEndpointDescriptorProjection.document, report)
        assertEquals(report, descriptor.encode().document)
    }

    @Test
    fun `the checked schema admits only the exact generated report contract`() {
        val schema = Files.readString(requiredPath(SCHEMA_PATH_PROPERTY))
        val report = Files.readString(requiredPath(REPORT_PATH_PROPERTY))

        assertEquals(CheckedSchemaAdmission.Admitted, admitCheckedSchemaReport(schema, report))
        assertSchemaRejected(
            schema.replace("\"additionalProperties\":false", "\"additionalProperties\":true"),
            report,
            CheckedSchemaFailure.INVALID_SCHEMA_AUTHORITY,
        )
        assertSchemaRejected(
            schema.replace("\"runtimeEpoch\", ", ""),
            report,
            CheckedSchemaFailure.INVALID_SCHEMA_AUTHORITY,
        )
        assertSchemaRejected(
            schema.replaceFirst("^sha256:[0-9a-f]{64}$", ".*"),
            report,
            CheckedSchemaFailure.INVALID_SCHEMA_AUTHORITY,
        )
        listOf(
            report.replaceFirst("{", "{\"unknown\":true,") to
                CheckedSchemaFailure.MALFORMED_REPORT,
            report.replace("\"runtimeEpoch\":0,", "") to
                CheckedSchemaFailure.MALFORMED_REPORT,
            report.replace("kast.ide.endpoint.v2", "kast.ide.endpoint.v1") to
                CheckedSchemaFailure.CONSTRAINT_MISMATCH,
            report.replace(FIXTURE_IDE_BUILD, "invalid") to
                CheckedSchemaFailure.CONSTRAINT_MISMATCH,
            report.replace("\"processId\":1", "\"processId\":0") to
                CheckedSchemaFailure.CONSTRAINT_MISMATCH,
            report.replace("/tmp/kast-ide.sock", "/" + "a".repeat(103)) to
                CheckedSchemaFailure.CONSTRAINT_MISMATCH,
            report.replace("/tmp/kast-ide.sock", "/" + "é".repeat(52)) to
                CheckedSchemaFailure.CONSTRAINT_MISMATCH,
            report.replace("/workspace/kast", "/" + "é".repeat(2_048)) to
                CheckedSchemaFailure.CONSTRAINT_MISMATCH,
            report.replace(
                "\"workspace.inspect\",\"symbol.discover\",\"symbol.resolve\",\"symbol.describe\"",
                "\"symbol.describe\",\"symbol.resolve\",\"symbol.discover\",\"workspace.inspect\"",
            ) to CheckedSchemaFailure.CONSTRAINT_MISMATCH,
        ).forEach { (invalidReport, failure) ->
            assertSchemaRejected(schema, invalidReport, failure)
        }
    }
}

internal const val REPORT_PATH_PROPERTY = "kast.ide.endpoint.report"
private const val SCHEMA_PATH_PROPERTY = "kast.ide.endpoint.schema"
internal const val FIXTURE_IDE_BUILD = "262.9437.185"
internal const val FIXTURE_KOTLIN_PLUGIN_BUILD = "262.9437.185-IJ"
internal const val FIXTURE_KAST_PLUGIN_VERSION = "1.2.3"
internal const val FIXTURE_RUNTIME_PROTOCOL = "kast.ide-hosted.runtime.v1"
internal const val FIXTURE_REGISTRY_DIGEST =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
internal const val FIXTURE_WIRE_DIGEST =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"

internal fun fixtureEndpointCandidate() = IdeEndpointDescriptorCandidate(
    schema = "kast.ide.endpoint.v2",
    canonicalRoot = "/workspace/kast",
    hostKind = "IDE_PROJECT",
    processId = 1,
    ideBuild = FIXTURE_IDE_BUILD,
    kotlinPluginBuild = FIXTURE_KOTLIN_PLUGIN_BUILD,
    kastPluginVersion = FIXTURE_KAST_PLUGIN_VERSION,
    runtimeProtocolIdentity = FIXTURE_RUNTIME_PROTOCOL,
    operationRegistryDigest = FIXTURE_REGISTRY_DIGEST,
    wireSchemaDigest = FIXTURE_WIRE_DIGEST,
    socketPath = "/tmp/kast-ide.sock",
    framing = "length-prefixed-json-v1",
    runtimeEpoch = 0,
    capabilities = listOf(
        "workspace.inspect",
        "symbol.discover",
        "symbol.resolve",
        "symbol.describe",
    ),
)

internal fun fixtureEndpointPolicy(): IdeHostCompatibilityPolicy = when (
    val refinement = IdeHostCompatibilityPolicy.define(
        IdeHostCompatibilityCandidate(
            ideBuild = FIXTURE_IDE_BUILD,
            kotlinPluginBuild = FIXTURE_KOTLIN_PLUGIN_BUILD,
            kastPluginVersion = FIXTURE_KAST_PLUGIN_VERSION,
            runtimeProtocolIdentity = FIXTURE_RUNTIME_PROTOCOL,
            operationRegistryDigest = FIXTURE_REGISTRY_DIGEST,
            wireSchemaDigest = FIXTURE_WIRE_DIGEST,
            capabilities = fixtureEndpointCandidate().capabilities,
        ),
    )
) {
    is Refinement.Refined -> refinement.value
    is Refinement.Rejected -> fail("fixture endpoint policy rejected: ${refinement.failure}")
}

internal fun IdeEndpointDescriptorAdmission.admittedDescriptor(): IdeEndpointDescriptorV2 =
    when (this) {
        is IdeEndpointDescriptorAdmission.Admitted -> descriptor
        is IdeEndpointDescriptorAdmission.Rejected -> fail("endpoint unexpectedly rejected: $failure")
    }

@Serializable
private data class CheckedIdeEndpointSchema(
    @SerialName("\$id") val id: String,
    @SerialName("\$schema") val dialect: String,
    val additionalProperties: Boolean,
    val properties: CheckedIdeEndpointProperties,
    val required: List<String>,
    val title: String,
    val type: String,
)

@Serializable
private data class CheckedIdeEndpointProperties(
    val canonicalRoot: CheckedPathConstraint,
    val capabilities: CheckedCapabilitiesConstraint,
    val framing: CheckedFixedStringConstraint,
    val hostKind: CheckedFixedStringConstraint,
    val ideBuild: CheckedPatternConstraint,
    val kastPluginVersion: CheckedPatternConstraint,
    val kotlinPluginBuild: CheckedPatternConstraint,
    val operationRegistryDigest: CheckedPatternConstraint,
    val processId: CheckedIntegerConstraint,
    val runtimeEpoch: CheckedIntegerConstraint,
    val runtimeProtocolIdentity: CheckedPatternConstraint,
    val schema: CheckedFixedStringConstraint,
    val socketPath: CheckedPathConstraint,
    val wireSchemaDigest: CheckedPatternConstraint,
)

@Serializable
private data class CheckedPathConstraint(
    val maxLength: Int,
    val minLength: Int,
    val pattern: String,
    val type: String,
)

@Serializable
private data class CheckedCapabilitiesConstraint(
    @SerialName("const") val exact: List<String>,
    val type: String,
)

@Serializable
private data class CheckedFixedStringConstraint(
    @SerialName("const") val exact: String,
    val type: String,
)

@Serializable
private data class CheckedPatternConstraint(
    val pattern: String,
    val type: String,
)

@Serializable
private data class CheckedIntegerConstraint(
    val minimum: Long,
    val type: String,
)

@Serializable
private data class CheckedIdeEndpointReport(
    val schema: String,
    val canonicalRoot: String,
    val hostKind: String,
    val processId: Long,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val socketPath: String,
    val framing: String,
    val runtimeEpoch: Long,
    val capabilities: List<String>,
)

private enum class CheckedSchemaFailure {
    MALFORMED_SCHEMA,
    INVALID_SCHEMA_AUTHORITY,
    MALFORMED_REPORT,
    CONSTRAINT_MISMATCH,
}

private sealed interface CheckedSchemaAdmission {
    data object Admitted : CheckedSchemaAdmission
    data class Rejected(val failure: CheckedSchemaFailure) : CheckedSchemaAdmission
}

private val checkedSchemaJson = Json {
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

private val endpointFieldOrder = listOf(
    "schema", "canonicalRoot", "hostKind", "processId", "ideBuild", "kotlinPluginBuild",
    "kastPluginVersion", "runtimeProtocolIdentity", "operationRegistryDigest", "wireSchemaDigest",
    "socketPath", "framing", "runtimeEpoch", "capabilities",
)
private val checkedCapabilities = listOf(
    "workspace.inspect", "symbol.discover", "symbol.resolve", "symbol.describe",
)
private const val CHECKED_PATH_PATTERN =
    "^(?!.*//)(?!.*(?:^|/)\\.{1,2}(?:/|$))/(?:[^/\\u0000]+(?:/[^/\\u0000]+)*)?$"
private const val CHECKED_IDE_BUILD_PATTERN = "^[0-9]{3}\\.[0-9]+\\.[0-9]+$"
private const val CHECKED_KOTLIN_BUILD_PATTERN = "^[0-9]{3}\\.[0-9]+\\.[0-9]+-IJ$"
private const val CHECKED_PLUGIN_VERSION_PATTERN =
    "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+-g[0-9a-f]{7,40})?$"
private const val CHECKED_RUNTIME_PATTERN =
    "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\\.v[1-9][0-9]*$"
private const val CHECKED_SHA256_PATTERN = "^sha256:[0-9a-f]{64}$"

private fun admitCheckedSchemaReport(
    schemaRaw: String,
    reportRaw: String,
): CheckedSchemaAdmission {
    val schema = try {
        checkedSchemaJson.decodeFromString(CheckedIdeEndpointSchema.serializer(), schemaRaw)
    } catch (_: SerializationException) {
        return CheckedSchemaAdmission.Rejected(CheckedSchemaFailure.MALFORMED_SCHEMA)
    } catch (_: IllegalArgumentException) {
        return CheckedSchemaAdmission.Rejected(CheckedSchemaFailure.MALFORMED_SCHEMA)
    }
    if (!schema.hasExactAuthorityShape()) {
        return CheckedSchemaAdmission.Rejected(CheckedSchemaFailure.INVALID_SCHEMA_AUTHORITY)
    }
    val report = try {
        checkedSchemaJson.decodeFromString(CheckedIdeEndpointReport.serializer(), reportRaw)
    } catch (_: SerializationException) {
        return CheckedSchemaAdmission.Rejected(CheckedSchemaFailure.MALFORMED_REPORT)
    } catch (_: IllegalArgumentException) {
        return CheckedSchemaAdmission.Rejected(CheckedSchemaFailure.MALFORMED_REPORT)
    }
    return if (schema.matches(report)) {
        CheckedSchemaAdmission.Admitted
    } else {
        CheckedSchemaAdmission.Rejected(CheckedSchemaFailure.CONSTRAINT_MISMATCH)
    }
}

private fun CheckedIdeEndpointSchema.hasExactAuthorityShape(): Boolean {
    val fields = properties
    return id == "https://kast.michne.com/schema/ide-endpoint-v2.json" &&
        dialect == "https://json-schema.org/draft/2020-12/schema" &&
        title == "Kast IDE project endpoint v2" && !additionalProperties && type == "object" &&
        required == endpointFieldOrder &&
        fields.canonicalRoot.hasShape(1, 4_096, CHECKED_PATH_PATTERN) &&
        fields.socketPath.hasShape(1, 103, CHECKED_PATH_PATTERN) &&
        fields.capabilities.type == "array" && fields.capabilities.exact == checkedCapabilities &&
        fields.processId.hasShape(1) && fields.runtimeEpoch.hasShape(0) &&
        fields.schema.hasShape("kast.ide.endpoint.v2") &&
        fields.hostKind.hasShape("IDE_PROJECT") &&
        fields.framing.hasShape("length-prefixed-json-v1") &&
        fields.ideBuild.hasShape(CHECKED_IDE_BUILD_PATTERN) &&
        fields.kotlinPluginBuild.hasShape(CHECKED_KOTLIN_BUILD_PATTERN) &&
        fields.kastPluginVersion.hasShape(CHECKED_PLUGIN_VERSION_PATTERN) &&
        fields.runtimeProtocolIdentity.hasShape(CHECKED_RUNTIME_PATTERN) &&
        fields.operationRegistryDigest.hasShape(CHECKED_SHA256_PATTERN) &&
        fields.wireSchemaDigest.hasShape(CHECKED_SHA256_PATTERN)
}

private fun CheckedIdeEndpointSchema.matches(report: CheckedIdeEndpointReport): Boolean = try {
    val fields = properties
    report.schema == fields.schema.exact &&
        fields.canonicalRoot.matches(report.canonicalRoot) &&
        report.hostKind == fields.hostKind.exact && report.processId >= fields.processId.minimum &&
        fields.ideBuild.matches(report.ideBuild) &&
        fields.kotlinPluginBuild.matches(report.kotlinPluginBuild) &&
        fields.kastPluginVersion.matches(report.kastPluginVersion) &&
        fields.runtimeProtocolIdentity.matches(report.runtimeProtocolIdentity) &&
        fields.operationRegistryDigest.matches(report.operationRegistryDigest) &&
        fields.wireSchemaDigest.matches(report.wireSchemaDigest) &&
        fields.socketPath.matches(report.socketPath) && report.framing == fields.framing.exact &&
        report.runtimeEpoch >= fields.runtimeEpoch.minimum && report.capabilities == fields.capabilities.exact
} catch (_: IllegalArgumentException) {
    false
}

private fun CheckedPathConstraint.hasShape(
    minimum: Int,
    maximum: Int,
    expectedPattern: String,
): Boolean = type == "string" && minLength == minimum && maxLength == maximum &&
    pattern == expectedPattern

private fun CheckedFixedStringConstraint.hasShape(expected: String): Boolean =
    type == "string" && exact == expected

private fun CheckedPatternConstraint.hasShape(expected: String): Boolean =
    type == "string" && pattern == expected

private fun CheckedIntegerConstraint.hasShape(expectedMinimum: Long): Boolean =
    type == "integer" && minimum == expectedMinimum

private fun CheckedPathConstraint.matches(raw: String): Boolean {
    val utf8ByteLength = raw.toByteArray(StandardCharsets.UTF_8).size
    return utf8ByteLength in minLength..maxLength && Regex(pattern).matches(raw)
}

private fun CheckedPatternConstraint.matches(raw: String): Boolean = Regex(pattern).matches(raw)

private fun requiredPath(property: String): Path = System.getProperty(property)
    ?.let(Path::of)
    ?: fail("missing $property path")

private fun assertSchemaRejected(
    schema: String,
    report: String,
    expected: CheckedSchemaFailure,
) {
    assertEquals(CheckedSchemaAdmission.Rejected(expected), admitCheckedSchemaReport(schema, report))
}
