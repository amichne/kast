package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Normalized absolute identity from Gradle metadata, never inferred from source-root containment. */
internal class GradleBuildIdentity private constructor(val path: Path) {
    override fun equals(other: Any?): Boolean = other is GradleBuildIdentity && path == other.path

    override fun hashCode(): Int = path.hashCode()

    companion object {
        fun selected(root: CanonicalWorkspaceRoot): GradleBuildIdentity = GradleBuildIdentity(Path.of(root.value))

        fun observe(raw: String?, limits: ReadLimits): Refinement<GradleBuildIdentity, GradleModuleOwnershipFailure> {
            if (raw.isNullOrBlank()) return Refinement.Rejected(GradleModuleOwnershipFailure.BUILD_ROOT_UNAVAILABLE)
            if (
                raw.length > limits[ReadLimitParameter.EPOCH_PATH_CHARACTERS].value ||
                    raw.toByteArray(Charsets.UTF_8).size > limits[ReadLimitParameter.EPOCH_PATH_BYTES].value
            )
                return Refinement.Rejected(GradleModuleOwnershipFailure.BUILD_ROOT_MALFORMED)
            val path =
                try {
                    Path.of(raw)
                } catch (_: InvalidPathException) {
                    return Refinement.Rejected(GradleModuleOwnershipFailure.BUILD_ROOT_MALFORMED)
                }
            if (!path.isAbsolute) return Refinement.Rejected(GradleModuleOwnershipFailure.BUILD_ROOT_MALFORMED)
            return Refinement.Refined(GradleBuildIdentity(path.normalize()))
        }
    }
}

@kotlinx.serialization.Serializable
enum class GradleModuleOwnershipFailure {
    GRADLE_OWNER_UNAVAILABLE,
    BUILD_ROOT_UNAVAILABLE,
    BUILD_ROOT_MALFORMED,
    MODULE_IDENTITY_MALFORMED,
    PROJECT_PATH_UNAVAILABLE,
    PROJECT_PATH_MALFORMED,
    CACHED_PROJECT_UNAVAILABLE,
}

internal sealed interface GradleBuildMembership {
    data object ForeignBuild : GradleBuildMembership

    class SelectedBuild private constructor(val build: GradleBuildIdentity) : GradleBuildMembership {
        companion object {
            fun classify(selected: GradleBuildIdentity, observed: GradleBuildIdentity): GradleBuildMembership =
                if (selected == observed) SelectedBuild(selected) else ForeignBuild
        }
    }
}

/** Only this admission boundary can bind a live module to the selected Gradle-build proof. */
internal class AdmittedSelectedBuildModule
private constructor(
    val module: Module,
    val identity: IntellijModuleIdentity,
    val ownership: GradleBuildMembership.SelectedBuild,
    val owner: CachedGradleProject,
) {
    companion object {
        fun admit(
            module: Module,
            index: GradleBuildProjectIndex,
            limits: ReadLimits,
        ): Refinement<SelectedGradleModuleAdmission, NamedGradleSourceScopeFailure> {
            if (module.isDisposed) return Refinement.Rejected(NamedGradleSourceScopeFailure.PROJECT_UNAVAILABLE)
            if (!ExternalSystemApiUtil.isExternalSystemAwareModule("GRADLE", module)) {
                return rejected(module, GradleModuleOwnershipFailure.GRADLE_OWNER_UNAVAILABLE, limits)
            }
            val build =
                when (
                    val parsed =
                        GradleBuildIdentity.observe(ExternalSystemApiUtil.getExternalRootProjectPath(module), limits)
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return rejected(module, parsed.failure, limits)
                }
            if (
                GradleBuildMembership.SelectedBuild.classify(index.selected, build) ==
                    GradleBuildMembership.ForeignBuild
            ) {
                return Refinement.Refined(SelectedGradleModuleAdmission.ForeignBuild)
            }
            val owner =
                when (val resolved = index.owner(ExternalSystemApiUtil.getExternalProjectPath(module))) {
                    is Refinement.Rejected -> return rejected(module, resolved.failure, limits)
                    is Refinement.Refined -> resolved.value
                }
            return when (val membership = GradleBuildMembership.SelectedBuild.classify(index.selected, owner.build)) {
                GradleBuildMembership.ForeignBuild -> Refinement.Refined(SelectedGradleModuleAdmission.ForeignBuild)
                is GradleBuildMembership.SelectedBuild -> {
                    val name = module.name
                    if (name.isBlank() || name.length > limits[ReadLimitParameter.MODEL_IDENTITY_CHARACTERS].value) {
                        return rejected(module, GradleModuleOwnershipFailure.MODULE_IDENTITY_MALFORMED, limits)
                    }
                    Refinement.Refined(
                        SelectedGradleModuleAdmission.SelectedBuild(
                            AdmittedSelectedBuildModule(
                                module = module,
                                identity = IntellijModuleIdentity(name),
                                ownership = membership,
                                owner = owner,
                            )
                        )
                    )
                }
            }
        }

        private fun rejected(module: Module, cause: GradleModuleOwnershipFailure, limits: ReadLimits) =
            Refinement.Rejected(
                NamedGradleSourceScopeFailure.ModuleOwnership(
                    BoundedModuleName.observe(module.name, limits),
                    cause,
                )
            )
    }
}

internal sealed interface SelectedGradleModuleAdmission {
    data object ForeignBuild : SelectedGradleModuleAdmission

    data class SelectedBuild(val module: AdmittedSelectedBuildModule) : SelectedGradleModuleAdmission
}
