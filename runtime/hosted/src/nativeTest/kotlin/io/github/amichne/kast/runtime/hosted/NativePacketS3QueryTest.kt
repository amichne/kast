package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import kotlinx.coroutines.runBlocking

/** Original S3 uses the existing real query coordinator and retained presentation with unchanged grants. */
class NativePacketS3QueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testOriginalS3SuccessStatusCodeReachesTheExactLoggerInfoArgument() = runBlocking {
        val fixture = prepare()
        val facts = fixture.s3Facts()
        NativePacketS3QueryOracle(facts).assertLogArgument(S3LogBranch.SUCCESS, capture(fixture, facts.loggerArgument))
    }

    fun testOriginalS3ErrorCodeReachesTheExactLoggerInfoArgument() = runBlocking {
        val fixture = prepare()
        val facts = fixture.s3Facts()
        NativePacketS3QueryOracle(facts).assertLogArgument(S3LogBranch.ERROR, capture(fixture, facts.loggerArgument))
    }

    fun testOriginalS3ExecuteReturnReachesItsMatchedOriginalCaller() = runBlocking {
        val fixture = prepare()
        val facts = fixture.s3Facts()
        NativePacketS3QueryOracle(facts).assertExecuteReturn(capture(fixture, facts.callerResult))
    }

    fun testOriginalS3EmittedWitnessesForbidHttpRequestArguments() = runBlocking {
        val fixture = prepare()
        val facts = fixture.s3Facts()
        NativePacketS3QueryOracle(facts).assertNoHttpArgumentWitness(capture(fixture, facts.callerResult))
    }

    private fun prepare(): NativePacketCompositeQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("packet-s3-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepareComposite()
    }

    private suspend fun capture(fixture: NativePacketCompositeQueryCase, required: ImpactValueSiteReferenceDocument) =
        NativePacketQueryCapture(fixture.services)
            .paths(
                producer = fixture.source.producerS3(),
                targets = listOf(required),
                outgoing = false,
                declarations = fixture.source.s3Declarations(),
            )
}
