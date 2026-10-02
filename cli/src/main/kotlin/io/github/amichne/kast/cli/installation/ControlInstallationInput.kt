package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerJvmUserHomeOption
import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement

internal data class ControlInstallationLocations(
    val ideaHome: InstallationPath,
    val javaHome: InstallationPath,
    val installRoot: UserInstallationRoot,
    val binDirectory: UserInstallationBin,
    val home: InstallationPath,
    val jvmUserHomeOption: BrokerJvmUserHomeOption,
    val codexHome: InstallationPath,
)

private data class ControlInstallationOwner(
    val home: InstallationPath,
    val jvmUserHomeOption: BrokerJvmUserHomeOption,
    val codexHome: InstallationPath,
)

private data class ControlInstallationSwitches(val force: InstallationSwitch, val controlOnly: InstallationSwitch)

internal data class ControlInstallationOptions(
    val endpoint: BrokerPublicEndpointMode,
    val profile: InstallationProfile,
    val force: InstallationSwitch,
    val controlOnly: InstallationSwitch,
    val mode: InstallationMode,
)

internal class ControlInstallationInput(private val environment: Map<String, String>) {
    private fun raw(name: InstallationEnvironment): Refinement<String, InstallationRequestFailure> =
        (environment[name.key]
                ?: if (name in setOf(InstallationEnvironment.FORCE, InstallationEnvironment.CONTROL_ONLY)) "0"
                else null)
            ?.let(Refinement<String, InstallationRequestFailure>::Refined)
            ?: Refinement.Rejected(InstallationRequestFailure.Missing(name))

    private fun path(name: InstallationEnvironment): Refinement<InstallationPath, InstallationRequestFailure> =
        when (val value = raw(name)) {
            is Refinement.Rejected -> value
            is Refinement.Refined ->
                when (val parsed = InstallationPath.parse(value.value)) {
                    is Refinement.Refined -> parsed
                    is Refinement.Rejected -> Refinement.Rejected(InstallationRequestFailure.InvalidPath(name))
                }
        }

    private fun digest(name: InstallationEnvironment): Refinement<Sha256, InstallationRequestFailure> =
        when (val value = raw(name)) {
            is Refinement.Rejected -> value
            is Refinement.Refined ->
                when (val parsed = Sha256.parse(value.value)) {
                    is Refinement.Refined -> parsed
                    is Refinement.Rejected -> Refinement.Rejected(InstallationRequestFailure.InvalidValue(name))
                }
        }

    private fun switch(name: InstallationEnvironment): Refinement<InstallationSwitch, InstallationRequestFailure> =
        when (val value = raw(name)) {
            is Refinement.Rejected -> value
            is Refinement.Refined ->
                when (value.value) {
                    "0" -> Refinement.Refined(InstallationSwitch.DISABLED)
                    "1" -> Refinement.Refined(InstallationSwitch.ENABLED)
                    else -> Refinement.Rejected(InstallationRequestFailure.InvalidValue(name))
                }
        }

    fun payload(): Refinement<ControlPayload, InstallationRequestFailure> {
        val controlRoot =
            when (val refined = path(InstallationEnvironment.CONTROL_ROOT)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val controlArchive =
            when (val refined = path(InstallationEnvironment.CONTROL_ARCHIVE)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val controlDigest =
            when (val refined = digest(InstallationEnvironment.CONTROL_SHA256)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        return Refinement.Refined(ControlPayload(root = controlRoot, archive = controlArchive, digest = controlDigest))
    }

    fun version(): Refinement<SemanticVersion, InstallationRequestFailure> {
        val versionRaw =
            when (val refined = raw(InstallationEnvironment.VERSION)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val version =
            when (val parsed = SemanticVersion.parse(versionRaw)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.VERSION))
            }
        return Refinement.Refined(version)
    }

    fun locations(): Refinement<ControlInstallationLocations, InstallationRequestFailure> {
        val ideaHome =
            when (val refined = path(InstallationEnvironment.IDEA_HOME)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val javaHome =
            when (val refined = path(InstallationEnvironment.JAVA_HOME)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val installRoot =
            when (val refined = path(InstallationEnvironment.INSTALL_ROOT)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val binDirectory =
            when (val refined = path(InstallationEnvironment.BIN_DIRECTORY)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val owner =
            when (val admitted = owner()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val userRoot =
            when (val admitted = UserInstallationRoot.admit(owner.home, installRoot)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val userBin =
            when (val admitted = UserInstallationBin.admit(owner.home, binDirectory)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return Refinement.Refined(
            ControlInstallationLocations(
                ideaHome = ideaHome,
                javaHome = javaHome,
                installRoot = userRoot,
                binDirectory = userBin,
                home = owner.home,
                jvmUserHomeOption = owner.jvmUserHomeOption,
                codexHome = owner.codexHome,
            )
        )
    }

    fun options(): Refinement<ControlInstallationOptions, InstallationRequestFailure> {
        val endpoint =
            when (
                val admitted = BrokerPublicEndpointMode.admit(environment[InstallationEnvironment.PUBLIC_ENDPOINT.key])
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        InstallationRequestFailure.InvalidValue(InstallationEnvironment.PUBLIC_ENDPOINT)
                    )
            }
        val profile =
            when (val admitted = profile()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val switches =
            when (val admitted = switches()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val mode =
            when (val admitted = mode()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        return Refinement.Refined(
            ControlInstallationOptions(
                endpoint = endpoint,
                profile = profile,
                force = switches.force,
                controlOnly = switches.controlOnly,
                mode = mode,
            )
        )
    }

    private fun owner(): Refinement<ControlInstallationOwner, InstallationRequestFailure> {
        val home =
            when (val refined = path(InstallationEnvironment.HOME)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val jvmUserHomeOption =
            when (val admitted = BrokerJvmUserHomeOption.from(home.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(InstallationRequestFailure.InvalidPath(InstallationEnvironment.HOME))
            }
        val codexHome =
            when (val refined = path(InstallationEnvironment.CODEX_HOME)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        return Refinement.Refined(
            ControlInstallationOwner(home = home, jvmUserHomeOption = jvmUserHomeOption, codexHome = codexHome)
        )
    }

    private fun switches(): Refinement<ControlInstallationSwitches, InstallationRequestFailure> {
        val force =
            when (val refined = switch(InstallationEnvironment.FORCE)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val controlOnly =
            when (val refined = switch(InstallationEnvironment.CONTROL_ONLY)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        if (controlOnly == InstallationSwitch.ENABLED && force == InstallationSwitch.ENABLED)
            return Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.CONTROL_ONLY))
        return Refinement.Refined(ControlInstallationSwitches(force, controlOnly))
    }

    private fun profile(): Refinement<InstallationProfile, InstallationRequestFailure> {
        val profile =
            when (environment[InstallationEnvironment.PROFILE.key]) {
                null,
                "persistent" -> InstallationProfile.PERSISTENT
                else ->
                    return Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.PROFILE))
            }
        return Refinement.Refined(profile)
    }

    private fun mode(): Refinement<InstallationMode, InstallationRequestFailure> {
        val modeRaw =
            when (val refined = raw(InstallationEnvironment.MODE)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val mode =
            when (modeRaw) {
                "plan" -> InstallationMode.PLAN
                "apply" -> InstallationMode.APPLY
                else ->
                    return Refinement.Rejected(InstallationRequestFailure.InvalidValue(InstallationEnvironment.MODE))
            }
        return Refinement.Refined(mode)
    }
}
