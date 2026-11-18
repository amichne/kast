package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import kotlinx.coroutines.runBlocking

class NativeJavaOriginFlowQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testJavaProducerReturnsToTheSelectedConsumer() = runBlocking {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("java-origin-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        val fixture =
            NativePacketQueryFixture(project = project, base = getOrCreateProjectBaseDir(), module = module, sdk = sdk)
                .prepare(NativePacketQueryVariant.JAVA_ORIGIN)
        val source = fixture.source
        val host = source.javaDeclaration("host", 0)
        val producer = source.javaDeclaration("source", 0)
        val sink = source.javaDeclaration("sink", 1)
        val expected =
            ImpactValueSiteReferenceDocument(
                host.declaration,
                source.javaCallRange("relay(source())"),
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(source.javaCallRange("sink(relay(source()))"), sink.declaration),
                    packetQueryOffset(0),
                ),
            )
        val result =
            NativePacketQueryCapture(fixture.services, flow = QueryImpactFlowDocument.KOTLIN_JAVA_FORWARD_V2)
                .capture(
                    producer =
                        QueryImpactProducerDocument(
                            host.reference,
                            producer.reference,
                            source.javaCallRange("source()"),
                        ),
                    targets = listOf(expected),
                    outgoing = false,
                    declarations = listOf(host, sink),
                )
        assertTrue(result.outcome.toString(), result.outcome is OperationOutcome.Complete)
        val path =
            result.paths.single { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().any {
                    it.transfer.target == expected
                }
            }
        val returned =
            path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().single {
                it.transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN
            }
        assertEquals(expected.range, returned.transfer.target.range)
    }
}
