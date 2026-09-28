package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val replaceBodyOnly: Boolean,
) {
    companion object {
        fun admit(arguments: Array<String>): NativeHostedChangeInputs {
            demand(
                arguments.size == 5 || (arguments.size == 6 && arguments[5] == "replace-body-only"),
                NativeFailure.INPUT_REJECTED,
            )
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
                replaceBodyOnly = arguments.size == 6,
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
                    inputs = inputs,
                    observeQualification = evidence::providerQualification,
                )
            execute(session)
            demand(session.trace.isolatedStartupCount() == 0, NativeFailure.PROVIDER_REJECTED)
            evidence.record("provider-routing", NativeCaseOutcome.PASSED, session.trace.document())
        } catch (rejected: NativeContractRejected) {
            evidence.contractRejected(rejected.document)
            failure = NativeFailure.CONTRACT_REJECTED
        } catch (_: NativeProviderQualificationRejected) {
            failure = NativeFailure.PROVIDER_QUALIFICATION_REJECTED
        } catch (rejected: NativeRejected) {
            failure = rejected.failure
        } catch (_: TimeoutCancellationException) {
            failure = NativeFailure.TIMEOUT
        } catch (linkage: LinkageError) {
            recordUnexpected(linkage)
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

    private suspend fun execute(session: NativeChangeSession) =
        withTimeout(900_000) {
            val source = inputs.workspace.resolve("src/main/kotlin/Fixture.kt")
            val controls = NativeFixtureControls(ioDispatcher)
            if (inputs.replaceBodyOnly) {
                NativeReplaceBodyWorkflow(source, evidence, controls, reopenAfterRestore = false).run(session.connect())
            } else {
                NativeChangeWorkflow(session, source, evidence, controls).run()
            }
        }

    private fun recordUnexpected(unexpected: Throwable) {
        privateWrite(
            inputs.privateDirectory.resolve("failure.private.json"),
            Json.encodeToString(NativePrivateFailureDocument.from(unexpected)),
        )
    }
}

@Serializable
internal data class NativePrivateFailureDocument(
    @SerialName("class") val className: String,
    val symbol: String?,
    val frames: List<NativePrivateFailureFrame>,
) {
    companion object {
        fun from(unexpected: Throwable): NativePrivateFailureDocument =
            NativePrivateFailureDocument(
                unexpected.javaClass.name,
                unexpected.message?.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9_.$()/;<>: -]+")) },
                unexpected.stackTrace.take(12).map { frame ->
                    NativePrivateFailureFrame(frame.className, frame.methodName, frame.lineNumber)
                },
            )
    }
}

@Serializable
internal data class NativePrivateFailureFrame(
    @SerialName("class") val className: String,
    val method: String,
    val line: Int,
)
