package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in real-artifact acceptance entry. Only bounded events go to stdout; raw calls stay in the private fixture. */
object NativeHostedChangeMain {
    @JvmStatic
    fun main(arguments: Array<String>): Unit = runBlocking {
        val failure = NativeHostedChangeRun(NativeHostedChangeInputs.admit(arguments)).run()
        if (failure != null) {
            println(
                buildJsonObject {
                    put("event", "rejected")
                    put("failure", failure.name)
                }
            )
            System.out.flush()
            kotlin.system.exitProcess(1)
        }
        println("""{"event":"completed"}""")
    }
}

internal data class NativeHostedChangeInputs(
    val product: Path,
    val workspace: Path,
    val schemas: Path,
    val privateDirectory: Path,
    val report: Path,
) {
    companion object {
        fun admit(arguments: Array<String>): NativeHostedChangeInputs {
            demand(arguments.size == 5, NativeFailure.INPUT_REJECTED)
            val workspace = Path.of(arguments[1]).toRealPath()
            val privateDirectory = Path.of(arguments[3]).toRealPath()
            val report = Path.of(arguments[4])
            demand(
                privateDirectory.startsWith(workspace.parent) && report.startsWith(privateDirectory),
                NativeFailure.INPUT_REJECTED,
            )
            return NativeHostedChangeInputs(
                product = Path.of(arguments[0]).toRealPath(),
                workspace = workspace,
                schemas = Path.of(arguments[2]).toRealPath(),
                privateDirectory = privateDirectory,
                report = report,
            )
        }
    }
}

private class NativeHostedChangeRun(
    private val inputs: NativeHostedChangeInputs,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val evidence = NativeChangeEvidence(inputs.report)

    suspend fun run(): NativeFailure? {
        evidence.metadata(
            buildJsonObject {
                put("upstream", "scripted-native-protocol-controller")
                put("provider", "staged-production-broker-cli-plugin")
                put("stockCodexUi", "unqualified")
                put("workspaceRoot", inputs.workspace.toString())
            }
        )
        var session: NativeChangeSession? = null
        var failure: NativeFailure? = null
        try {
            session =
                NativeChangeSession.open(
                    product = inputs.product,
                    workspace = inputs.workspace,
                    home = Path.of(System.getProperty("user.home")).toRealPath(),
                    schemas = inputs.schemas,
                    privateDirectory = inputs.privateDirectory,
                )
            withTimeout(900_000) {
                NativeChangeWorkflow(
                        session = session,
                        source = inputs.workspace.resolve("src/main/kotlin/Fixture.kt"),
                        evidence = evidence,
                        controls = NativeFixtureControls(ioDispatcher),
                    )
                    .run()
            }
            demand(session.trace.isolatedStartupCount() == 0, NativeFailure.PROVIDER_REJECTED)
            evidence.record("provider-routing", NativeCaseOutcome.PASSED, session.trace.document())
        } catch (rejected: NativeContractRejected) {
            evidence.contractRejected(rejected.document)
            failure = NativeFailure.CONTRACT_REJECTED
        } catch (rejected: NativeRejected) {
            failure = rejected.failure
        } catch (_: TimeoutCancellationException) {
            failure = NativeFailure.TIMEOUT
        } catch (_: LinkageError) {
            failure = NativeFailure.ARTIFACT_INCOMPATIBLE
        } catch (unexpected: Exception) {
            recordUnexpected(unexpected)
            failure = NativeFailure.INTERNAL_FAILURE
        } finally {
            session?.close()
            evidence.terminal(failure)
        }
        return failure
    }

    private fun recordUnexpected(unexpected: Exception) {
        privateWrite(
            inputs.privateDirectory.resolve("failure.private.json"),
            buildJsonObject {
                put("class", unexpected.javaClass.name)
                put(
                    "frames",
                    buildJsonArray {
                        unexpected.stackTrace.take(12).forEach { frame ->
                            add(
                                buildJsonObject {
                                    put("class", frame.className)
                                    put("method", frame.methodName)
                                    put("line", frame.lineNumber)
                                }
                            )
                        }
                    },
                )
            }
                .toString(),
        )
    }
}
