package io.github.amichne.kast.appserver.host.admission

import io.github.amichne.kast.appserver.provider.BrokerExecutable
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal enum class CodexHostExecutableFailure {
    UNAVAILABLE,
    RECURSIVE_FACADE,
    IDENTITY_REJECTED,
}

internal enum class CodexExecutableIdentity {
    SAME,
    DISTINCT,
    REJECTED,
}

/** Exact executable installed as Desktop's process-local Codex CLI substitution. */
@JvmInline
internal value class DesktopFacadeExecutable private constructor(
    private val executable: BrokerExecutable,
) {
    val path: Path get() = executable.path

    internal fun compareIdentity(candidate: BrokerExecutable): CodexExecutableIdentity = try {
        if (Files.isSameFile(executable.path, candidate.path)) {
            CodexExecutableIdentity.SAME
        } else {
            CodexExecutableIdentity.DISTINCT
        }
    } catch (_: IOException) {
        CodexExecutableIdentity.REJECTED
    } catch (_: SecurityException) {
        CodexExecutableIdentity.REJECTED
    }

    companion object {
        internal fun admit(
            candidate: Path,
        ): Refinement<DesktopFacadeExecutable, CodexHostExecutableFailure> =
            when (val admission = BrokerExecutable.admit(candidate)) {
                is Refinement.Refined -> Refinement.Refined(
                    DesktopFacadeExecutable(admission.value),
                )
                is Refinement.Rejected -> Refinement.Rejected(
                    CodexHostExecutableFailure.UNAVAILABLE,
                )
            }
    }
}

/** All executable identities that could be selected as this installation's Desktop façade. */
internal class DesktopFacadeExecutables private constructor(
    internal val values: List<DesktopFacadeExecutable>,
) {
    companion object {
        internal fun none(): DesktopFacadeExecutables = DesktopFacadeExecutables(emptyList())

        internal fun resolve(
            installedFacadeCandidate: Path,
            configuredFacade: String?,
        ): DesktopFacadeExecutables {
            val configuredCandidate = configuredFacade?.let { raw ->
                try {
                    Path.of(raw).takeIf { path -> path.isAbsolute && path.normalize() == path }
                } catch (_: RuntimeException) {
                    null
                }
            }
            val values = listOfNotNull(configuredCandidate, installedFacadeCandidate)
                .mapNotNull { candidate ->
                    when (val admission = DesktopFacadeExecutable.admit(candidate)) {
                        is Refinement.Refined -> admission.value
                        is Refinement.Rejected -> null
                    }
                }
                .distinctBy(DesktopFacadeExecutable::path)
            return DesktopFacadeExecutables(values)
        }
    }
}

/** Real Codex executable proven distinct from the Desktop façade. */
internal class UpstreamCodexExecutable private constructor(
    internal val executable: BrokerExecutable,
    /** Original launcher path retains interpreter discovery across a desktop child. */
    val launcherPath: Path,
) {
    val path: Path get() = executable.path

    companion object {
        internal fun admit(
            candidate: Path,
            facades: DesktopFacadeExecutables,
            launcherCandidate: Path = candidate,
        ): Refinement<UpstreamCodexExecutable, CodexHostExecutableFailure> =
            when (val admission = BrokerExecutable.admit(candidate)) {
                is Refinement.Rejected -> Refinement.Rejected(
                    CodexHostExecutableFailure.UNAVAILABLE,
                )
                is Refinement.Refined -> facades.values
                    .map { facade -> facade.compareIdentity(admission.value) }
                    .let { identities ->
                        when {
                            CodexExecutableIdentity.REJECTED in identities ->
                                Refinement.Rejected(
                                    CodexHostExecutableFailure.IDENTITY_REJECTED,
                                )
                            CodexExecutableIdentity.SAME in identities -> Refinement.Rejected(
                                CodexHostExecutableFailure.RECURSIVE_FACADE,
                            )
                            else -> retainLauncher(admission.value, launcherCandidate)
                        }
                    }
            }

        private fun retainLauncher(
            executable: BrokerExecutable,
            launcher: Path,
        ): Refinement<UpstreamCodexExecutable, CodexHostExecutableFailure> = try {
            if (Files.isSameFile(executable.path, launcher)) {
                Refinement.Refined(UpstreamCodexExecutable(executable, launcher))
            } else {
                Refinement.Rejected(CodexHostExecutableFailure.IDENTITY_REJECTED)
            }
        } catch (_: IOException) {
            Refinement.Rejected(CodexHostExecutableFailure.IDENTITY_REJECTED)
        } catch (_: SecurityException) {
            Refinement.Rejected(CodexHostExecutableFailure.IDENTITY_REJECTED)
        }
    }
}
