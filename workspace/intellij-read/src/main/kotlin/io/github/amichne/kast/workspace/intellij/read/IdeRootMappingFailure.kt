package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance

/** Diagnostic identities preserve bounded observations even when admission of the value itself fails. */
data class BoundedModuleName private constructor(val value: String, val truncated: Boolean) {
    companion object {
        internal fun observe(raw: String, limits: ReadLimits = ReadLimits.Default): BoundedModuleName {
            val bound = limits[ReadLimitParameter.MODEL_IDENTITY_CHARACTERS].value.coerceAtMost(512)
            return BoundedModuleName(raw.take(bound), raw.length > bound)
        }
    }
}

data class BoundedSourceRootIdentity private constructor(val value: String, val truncated: Boolean) {
    companion object {
        internal fun observe(raw: String, limits: ReadLimits = ReadLimits.Default): BoundedSourceRootIdentity {
            val bound = limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value.coerceAtMost(8192)
            return BoundedSourceRootIdentity(raw.take(bound), raw.length > bound)
        }
    }
}

data class IdeSourceRootEvidence(val module: BoundedModuleName, val root: BoundedSourceRootIdentity)

data class CodeSourceRootClassification(
    val kind: WorkspaceSourceRootKind,
    val provenance: WorkspaceSourceRootProvenance,
)

sealed interface IdeRootMappingFailure {
    val evidence: IdeSourceRootEvidence

    data class SourceFolderUnavailable(override val evidence: IdeSourceRootEvidence) : IdeRootMappingFailure

    data class SourceFolderObservationFailed(override val evidence: IdeSourceRootEvidence) : IdeRootMappingFailure

    data class GradleOwnerMissing(override val evidence: IdeSourceRootEvidence) : IdeRootMappingFailure

    data class UnsupportedRootType(override val evidence: IdeSourceRootEvidence) : IdeRootMappingFailure

    data class SourcePropertiesUnavailable(override val evidence: IdeSourceRootEvidence) : IdeRootMappingFailure

    data class RootClassificationMismatch(
        override val evidence: IdeSourceRootEvidence,
        val ide: CodeSourceRootClassification,
        val gradle: CodeSourceRootClassification,
    ) : IdeRootMappingFailure
}
