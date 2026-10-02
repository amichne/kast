package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusFailure
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusField
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusSyntax
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DaemonManagementRuntimeProjectionTest {
    @Test
    fun `every finite compatibility rejection retains its required discriminator and case fields`() {
        val cases =
            listOf(
                Triple(
                    HostedCompatibilityStatusFailure.Malformed(
                        HostedCompatibilityStatusField.WIRE_SCHEMA_DIGEST,
                        HostedCompatibilityStatusSyntax.INVALID_FORMAT,
                    ),
                    "MALFORMED",
                    setOf("type", "field", "syntax"),
                ),
                Triple(
                    HostedCompatibilityStatusFailure.UnknownCapability("query.unknown"),
                    "UNKNOWN_CAPABILITY",
                    setOf("type", "operationId"),
                ),
                Triple(
                    HostedCompatibilityStatusFailure.UnsupportedCapability("query.run"),
                    "UNSUPPORTED_CAPABILITY",
                    setOf("type", "operationId"),
                ),
                Triple(
                    HostedCompatibilityStatusFailure.DuplicateCapability("query.run"),
                    "DUPLICATE_CAPABILITY",
                    setOf("type", "operationId"),
                ),
            )
        cases.forEach { (failure, type, fields) ->
            val document =
                Json.parseToJsonElement(
                        DaemonManagementProtocol.json.encodeToString(
                            HostedCompatibilityStatusFailure.serializer(),
                            failure,
                        )
                    )
                    .jsonObject
            assertEquals(type, document.getValue("type").jsonPrimitive.content)
            assertEquals(fields, document.keys)
        }
    }

    @Test
    fun `mixed host versions encode independent provenance and finite compatibility outcomes`() {
        val compatible = HostedServiceStatus.Compatible("/one", "00000000-0000-0000-0000-000000000001", 101, "0.49.0")
        val mismatch =
            HostedCompatibilityStatusFailure.Mismatch(
                HostedCompatibilityStatusField.RUNTIME_PROTOCOL_IDENTITY,
                listOf("kast.ide-host.v4"),
                listOf("kast.ide-host.v3"),
            )
        val incompatible =
            HostedServiceStatus.Incompatible(
                "/two",
                "00000000-0000-0000-0000-000000000002",
                202,
                "0.48.0",
                mismatch,
            )
        val unavailable = HostedServiceStatus.Unavailable("/three", HostedServiceUnavailableFailure.HOST_UNAVAILABLE)
        val document =
            Json.parseToJsonElement(
                    DaemonManagementProtocol.json.encodeToString(
                        ManagementRuntimeProjection(
                            "0.50.0",
                            emptyList(),
                            0,
                            listOf(compatible, incompatible, unavailable),
                        )
                    )
                )
                .jsonObject
        assertEquals("0.50.0", document.getValue("loadedVersion").jsonPrimitive.content)
        val hosts = document.getValue("hostedServices").jsonArray.map { it.jsonObject }
        assertEquals(setOf("type", "root", "host", "hostPid", "hostedPluginVersion"), hosts[0].keys)
        assertEquals("COMPATIBLE", hosts[0].getValue("type").jsonPrimitive.content)
        assertEquals("0.49.0", hosts[0].getValue("hostedPluginVersion").jsonPrimitive.content)
        assertEquals("INCOMPATIBLE", hosts[1].getValue("type").jsonPrimitive.content)
        assertEquals("0.48.0", hosts[1].getValue("hostedPluginVersion").jsonPrimitive.content)
        val failure = hosts[1].getValue("failure").jsonObject
        assertEquals(setOf("type", "field", "expected", "observed"), failure.keys)
        assertEquals("MISMATCH", failure.getValue("type").jsonPrimitive.content)
        assertEquals("RUNTIME_PROTOCOL_IDENTITY", failure.getValue("field").jsonPrimitive.content)
        assertEquals("kast.ide-host.v4", failure.getValue("expected").jsonArray.single().jsonPrimitive.content)
        assertEquals("kast.ide-host.v3", failure.getValue("observed").jsonArray.single().jsonPrimitive.content)
        assertEquals(setOf("type", "root", "failure"), hosts[2].keys)
        assertEquals("UNAVAILABLE", hosts[2].getValue("type").jsonPrimitive.content)
        assertEquals("HOST_UNAVAILABLE", hosts[2].getValue("failure").jsonPrimitive.content)
    }

    @Test
    fun `registry failure encodes its exact cause and path without inventing a host workspace`() {
        val status: HostedServiceStatus =
            HostedServiceStatus.RegistryUnavailable(
                "/installation/config/workspaces.json",
                io.github.amichne.kast.distribution.contract.HostedRegistryFailure.PATH_REJECTED,
            )
        val document =
            Json.parseToJsonElement(
                    DaemonManagementProtocol.json.encodeToString(HostedServiceStatus.serializer(), status)
                )
                .jsonObject
        assertEquals(setOf("type", "registryPath", "failure"), document.keys)
        assertEquals("REGISTRY_UNAVAILABLE", document.getValue("type").jsonPrimitive.content)
        assertEquals("/installation/config/workspaces.json", document.getValue("registryPath").jsonPrimitive.content)
        assertEquals("PATH_REJECTED", document.getValue("failure").jsonPrimitive.content)
    }

    @Test
    fun `status encodes the passive runtime projection with an exact shape`() {
        val target =
            DaemonManagementTarget(
                "sha256:${"a".repeat(64)}",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "b".repeat(64),
            )
        val status =
            CoordinatorStatusDocument(
                CoordinatorServiceState.READY,
                target.installationId,
                target.stateEpoch,
                target.serviceGeneration,
                target.configurationIdentity,
                0,
                0,
                emptyList(),
                CoordinatorHostAttachment.PENDING,
            )
        val response =
            DaemonManagementResponse.Status(status, ManagementRuntimeProjection("1.2.3", listOf("/workspace"), 2))
        val document =
            Json.parseToJsonElement(
                    DaemonManagementProtocol.json.encodeToString(DaemonManagementResponse.serializer(), response)
                )
                .jsonObject
        assertEquals(setOf("type", "coordinator", "projection"), document.keys)
        val runtime = document.getValue("projection").jsonObject
        assertEquals(setOf("loadedVersion", "activeWorkspaces", "liveConnections", "hostedServices"), runtime.keys)
        assertEquals("1.2.3", runtime.getValue("loadedVersion").jsonPrimitive.content)
        assertEquals("/workspace", runtime.getValue("activeWorkspaces").jsonArray.single().jsonPrimitive.content)
        assertEquals(2, runtime.getValue("liveConnections").jsonPrimitive.content.toInt())
    }
}
