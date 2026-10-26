package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk

abstract class NativeCompilerCoverageFixtureTest : NativeSmartModeFixtureTest() {
    abstract override fun getModuleType(): ModuleType<*>

    internal fun prepare(): NativePacketQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("compiler-coverage-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativePacketQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(NativePacketQueryVariant.COVERAGE)
    }
}
