package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.facet.getOrCreateConfiguredFacet

abstract class NativeCompilerCoverageFixtureTest : NativeSmartModeFixtureTest() {
    abstract override fun getModuleType(): ModuleType<*>

    internal fun prepare(
        variant: NativePacketQueryVariant = NativePacketQueryVariant.COVERAGE,
        configureCompiler: () -> Unit = {},
    ): NativePacketQueryCase {
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
                configureCompiler = configureCompiler,
            )
            .prepare(variant)
    }

    internal fun prepareConfigured(): NativePacketQueryCase =
        prepare(NativePacketQueryVariant.CONFIGURED) {
            val models = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
            try {
                module.getOrCreateConfiguredFacet(models, useProjectSettings = false, commitModel = true) {
                    configuration.settings.compilerArguments =
                        K2JVMCompilerArguments().apply {
                            languageVersion = "2.4"
                            apiVersion = "2.3"
                            autoAdvanceLanguageVersion = false
                            autoAdvanceApiVersion = false
                            nameBasedDestructuring = "complete"
                        }
                }
            } finally {
                models.dispose()
            }
            val settings = module.languageVersionSettings
            assertEquals(LanguageVersion.KOTLIN_2_4, settings.languageVersion)
            assertEquals(ApiVersion.KOTLIN_2_3, settings.apiVersion)
            assertTrue(settings.supportsFeature(LanguageFeature.NameBasedDestructuring))
            assertTrue(settings.supportsFeature(LanguageFeature.EnableNameBasedDestructuringShortForm))
        }
}
