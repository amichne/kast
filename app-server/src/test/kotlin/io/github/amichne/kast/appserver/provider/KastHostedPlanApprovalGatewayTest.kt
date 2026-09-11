package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.runtime.HostedChangeApprovalOperation
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastHostedPlanApprovalGatewayTest {
    @Test
    fun `prepare projects only canonical plan identity and binds independent owner facts`(@TempDir home: Path) =
        runBlocking {
            val requests = mutableListOf<BrokerProcessRequest>()
            val request = request(home)
            val gateway =
                gateway(home) { process ->
                    requests += process
                    BrokerProcessExecution.Completed(0, response(home).toString(), "")
                }
            val result = (gateway.prepare(request) as Refinement.Refined).value
            assertSame(request, result.request)
            assertEquals("a".repeat(64), result.subject.planIdentity)
            assertEquals("src/Main.kt", result.preview.path.value)
            assertEquals(listOf("change", "apply", "--hosted-approval-prepare"), requests.single().arguments)
            assertEquals(request.arguments.toString(), (requests.single().input as BrokerProcessInput.Document).value)
            assertEquals(request.invocation.workingDirectory, requests.single().workingDirectory)
        }

    @Test
    fun `prepare rejects wrong plan operation owner malformed response and escaping preview`(@TempDir home: Path) =
        runBlocking {
            val normal = response(home)
            listOf(
                    JsonObject(normal + ("planId" to JsonPrimitive("c".repeat(64)))),
                    JsonObject(normal + ("operation" to JsonPrimitive("CHANGE_RECOVER"))),
                    JsonObject(normal + ("host" to JsonPrimitive("not-a-host"))),
                    JsonObject(normal + ("approved" to JsonPrimitive(true))),
                    JsonObject(
                        normal +
                            ("preview" to
                                buildJsonObject {
                                    put("path", "../outside.kt")
                                    put("diff", "diff")
                                })
                    ),
                    buildJsonObject {},
                )
                .forEach { response ->
                    val gateway = gateway(home) { BrokerProcessExecution.Completed(0, response.toString(), "") }
                    assertInstanceOf(Refinement.Rejected::class.java, gateway.prepare(request(home)))
                }
            val gateway = gateway(home) { BrokerProcessExecution.Completed(2, normal.toString(), "private details") }
            assertEquals(
                Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE),
                gateway.prepare(request(home)),
            )
        }

    @Test
    fun `missing signing enrollment rejects before any process or prompt`(@TempDir home: Path) = runBlocking {
        var invoked = false
        val options =
            options(home) {
                invoked = true
                BrokerProcessExecution.Rejected(BrokerProcessFailure.SPAWN_FAILED)
            }
        assertEquals(
            Refinement.Rejected(HostedPlanApprovalFailure.SIGNING_UNAVAILABLE),
            KastHostedPlanApprovalGateway(options, home).prepare(request(home)),
        )
        assertFalse(invoked)
    }

    private fun request(home: Path): HostedPlanApprovalRequest {
        val invocation =
            (BrokerInvocationContext.admit(
                    threadId = "thread",
                    turnId = "turn",
                    callId = "call",
                    workingDirectory = home.toRealPath(),
                ) as Refinement.Refined)
                .value
        return (HostedPlanApprovalRequest.admit(
                HostedChangeApprovalOperation.APPLY,
                invocation,
                buildJsonObject { put("planIdentity", "plan:${"a".repeat(64)}") },
            ) as Refinement.Refined)
            .value
    }

    private fun response(home: Path) = buildJsonObject {
        put("version", 1)
        put("operation", "CHANGE_APPLY")
        put("root", home.toRealPath().toString())
        put("host", "11111111-1111-1111-1111-111111111111")
        put("planId", "a".repeat(64))
        put("challenge", "b".repeat(64))
        put(
            "preview",
            buildJsonObject {
                put("path", "src/Main.kt")
                put("diff", "@@ -1 +1 @@\n-old\n+new\n")
            },
        )
    }

    private fun gateway(home: Path, executor: BrokerProcessExecutor): KastHostedPlanApprovalGateway {
        val directory = Files.createDirectories(home.resolve(".kast/approval"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        listOf("broker.pk8" to pair.private.encoded, "broker.pub" to pair.public.encoded).forEach { (name, bytes) ->
            Files.write(directory.resolve(name), bytes)
            Files.setPosixFilePermissions(directory.resolve(name), PosixFilePermissions.fromString("rw-------"))
        }
        return KastHostedPlanApprovalGateway(options(home, executor), home)
    }

    private fun options(home: Path, executor: BrokerProcessExecutor): KastProviderOptions {
        val executable = home.resolve("kast")
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        executable.toFile().setExecutable(true)
        return (KastProviderOptions.admit(executable.toRealPath(), home.toRealPath(), executor) as Refinement.Refined)
            .value
    }
}
