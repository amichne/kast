package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JdkFinder
import com.intellij.openapi.projectRoots.ProjectJdkTable
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.plugins.gradle.properties.GradleLocalPropertiesFile
import org.jetbrains.plugins.gradle.properties.GradlePropertiesFile
import org.jetbrains.plugins.gradle.util.JavaHomeValidationStatus
import org.jetbrains.plugins.gradle.util.USE_GRADLE_JAVA_HOME
import org.jetbrains.plugins.gradle.util.USE_GRADLE_LOCAL_JAVA_HOME

internal fun privateGradleJvmDeclaration(project: Project, root: Path): PrivateGradleJvmDeclaration {
    val declared = GradlePropertiesFile.getProperties(project, root).javaHomeProperty
    val local = GradleLocalPropertiesFile.getProperties(root).javaHomeProperty
    return when {
        declared != null -> PrivateGradleJvmDeclaration.GradleProperty(declared.value)
        local != null -> PrivateGradleJvmDeclaration.LocalProperty(local.value)
        else -> PrivateGradleJvmDeclaration.Unspecified
    }
}

/** The caller stops at the first compatible home; explicit declarations never initiate discovery. */
internal fun privateGradleJvmHomes(
    declaration: PrivateGradleJvmDeclaration,
    bundledHome: String,
    discover: () -> List<String>,
): Sequence<String> =
    when (declaration) {
        is PrivateGradleJvmDeclaration.GradleProperty -> sequenceOf(declaration.home)
        is PrivateGradleJvmDeclaration.LocalProperty -> sequenceOf(declaration.home)
        PrivateGradleJvmDeclaration.Unspecified ->
            sequence {
                yield(bundledHome)
                yieldAll(discover().distinct().filter { it != bundledHome })
            }
    }

/** Native discovery is local only; no build scripts are parsed and no JDK is downloaded. */
internal fun discoverPrivateGradleJvms(project: Project): List<String> =
    ProjectJdkTable.getInstance().allJdks.filter { it.sdkType == JavaSdk.getInstance() }.mapNotNull { it.homePath } +
        JdkFinder.getInstance().suggestHomePaths(project).sorted()

internal fun JavaHomeValidationStatus.privateGradleCompatibility(): GradleJavaHomeCompatibility =
    when (this) {
        is JavaHomeValidationStatus.Success -> GradleJavaHomeCompatibility.COMPATIBLE
        JavaHomeValidationStatus.Invalid,
        JavaHomeValidationStatus.Undefined -> GradleJavaHomeCompatibility.INVALID_HOME
        is JavaHomeValidationStatus.Unsupported -> GradleJavaHomeCompatibility.UNSUPPORTED_JAVA
    }

internal fun privateGradleJvmBinding(
    declaration: PrivateGradleJvmDeclaration,
    bundledHome: String,
    observations: Map<String, GradleJavaHomeCompatibility>,
): Refinement<PrivateGradleJvmBinding, PrivateGradleJvmFailure> =
    when (val selected = PrivateGradleJvm.select(declaration, bundledHome, observations)) {
        is Refinement.Rejected -> selected
        is Refinement.Refined -> PrivateGradleJvmBinding.bind(selected.value)
    }

internal fun rejectPrivateGradleJvmSettings(
    failure: PrivateGradleJvmFailure
): Refinement.Rejected<io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure> {
    recordPrivateGradleJvm(PrivateGradleJvmObservation.Rejected(failure))
    return Refinement.Rejected(io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure.EFFECT_FAILED)
}

/** A native SDK reference bound to the selected home, ready for the existing import owner to persist. */
internal class PrivateGradleJvmBinding
private constructor(val sdkReference: String, val authority: PrivateGradleJvmAuthority) {
    companion object {
        fun bind(selected: PrivateGradleJvm): Refinement<PrivateGradleJvmBinding, PrivateGradleJvmFailure> =
            when (selected.authority) {
                PrivateGradleJvmAuthority.GRADLE_PROPERTY ->
                    Refinement.Refined(PrivateGradleJvmBinding(USE_GRADLE_JAVA_HOME, selected.authority))
                PrivateGradleJvmAuthority.LOCAL_PROPERTY ->
                    Refinement.Refined(PrivateGradleJvmBinding(USE_GRADLE_LOCAL_JAVA_HOME, selected.authority))
                PrivateGradleJvmAuthority.BUNDLED_RUNTIME,
                PrivateGradleJvmAuthority.LOCAL_INSTALLATION -> register(selected)
            }

        private fun register(selected: PrivateGradleJvm): Refinement<PrivateGradleJvmBinding, PrivateGradleJvmFailure> {
            val java = JavaSdk.getInstance()
            val table = ProjectJdkTable.getInstance()
            val existing = table.allJdks.filter { it.homePath == selected.home && it.sdkType == java }
            if (existing.size > 1) return Refinement.Rejected(PrivateGradleJvmFailure.AMBIGUOUS_SDK_REGISTRATION)
            val name = "Kast Gradle: ${selected.home}"
            if (existing.isEmpty() && table.findJdk(name) != null)
                return Refinement.Rejected(PrivateGradleJvmFailure.SDK_NAME_CONFLICT)
            val sdk = existing.singleOrNull() ?: java.createJdk(name, selected.home, false)
            WriteAction.run<RuntimeException> {
                if (existing.isEmpty()) table.addJdk(sdk)
            }
            return Refinement.Refined(PrivateGradleJvmBinding(sdk.name, selected.authority))
        }
    }
}

@Serializable
internal sealed interface PrivateGradleJvmObservation {
    @Serializable @SerialName("daemon_criteria") data object DaemonCriteria : PrivateGradleJvmObservation

    @Serializable
    @SerialName("selected")
    data class Selected(val authority: PrivateGradleJvmAuthority) : PrivateGradleJvmObservation

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: PrivateGradleJvmFailure) : PrivateGradleJvmObservation
}

internal fun recordPrivateGradleJvm(observation: PrivateGradleJvmObservation) {
    Logger.getInstance(IntellijWorkspaceRefreshPort::class.java)
        .info("kast_private_gradle_jvm " + Json.encodeToString(PrivateGradleJvmObservation.serializer(), observation))
}
