package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModernApplicationStarter
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Key
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

@kotlinx.serialization.Serializable
internal enum class HeadlessHostFailure {
    UNSAFE_BOOTSTRAP,
    INVALID_PATH,
    SHARED_STATE,
    FOREIGN_JBR,
}

/** Captured Platform facts, before the application creates its lifecycle endpoint. */
internal data class HeadlessHostEnvironment(
    val state: Path,
    val installation: Path,
    val javaHome: Path,
    val home: Path,
    val config: Path,
    val system: Path,
    val plugins: Path,
    val log: Path,
    val temporary: Path,
    val productionBootstrap: Boolean,
)

/** Admission grants private process state, never project trust or semantic readiness. */
internal class ManagedHeadlessHost private constructor(val environment: HeadlessHostEnvironment) {
    companion object {
        private val KEY = Key.create<ManagedHeadlessHost>("kast.managed.headless.host")

        fun current(): ManagedHeadlessHost? = ApplicationManager.getApplication().getUserData(KEY)

        fun admit(environment: HeadlessHostEnvironment): Refinement<ManagedHeadlessHost, HeadlessHostFailure> {
            val state = environment.state
            val mutable =
                listOf(
                    "home" to environment.home,
                    "system" to environment.system,
                    "log" to environment.log,
                    "tmp" to environment.temporary,
                )
            val paths =
                listOf(state, environment.installation, environment.javaHome, environment.plugins, environment.config) +
                    mutable.map { it.second }
            return when {
                !environment.productionBootstrap -> Refinement.Rejected(HeadlessHostFailure.UNSAFE_BOOTSTRAP)
                paths.any { !it.isAbsolute || it.normalize() != it } ->
                    Refinement.Rejected(HeadlessHostFailure.INVALID_PATH)
                state.startsWith(environment.installation) || environment.installation.startsWith(state) ->
                    Refinement.Rejected(HeadlessHostFailure.SHARED_STATE)
                mutable.any { (name, path) -> path != state.resolve(name) } ->
                    Refinement.Rejected(HeadlessHostFailure.SHARED_STATE)
                environment.plugins.fileName?.toString() != "plugins" ->
                    Refinement.Rejected(HeadlessHostFailure.SHARED_STATE)
                !environment.plugins.startsWith(environment.home) ->
                    Refinement.Rejected(HeadlessHostFailure.SHARED_STATE)
                environment.config != environment.plugins.parent ->
                    Refinement.Rejected(HeadlessHostFailure.SHARED_STATE)
                environment.javaHome !in
                    setOf(
                        environment.installation.resolve("jbr"),
                        environment.installation.resolve("jbr/Contents/Home"),
                    ) -> Refinement.Rejected(HeadlessHostFailure.FOREIGN_JBR)
                else -> Refinement.Refined(ManagedHeadlessHost(environment))
            }
        }

        fun start(): Refinement<ManagedHeadlessHost, HeadlessHostFailure> =
            when (val captured = capture()) {
                is Refinement.Rejected -> captured
                is Refinement.Refined ->
                    when (val admitted = admit(captured.value)) {
                        is Refinement.Rejected -> admitted
                        is Refinement.Refined -> {
                            ApplicationManager.getApplication().putUserData(KEY, admitted.value)
                            admitted
                        }
                    }
            }

        private fun capture(): Refinement<HeadlessHostEnvironment, HeadlessHostFailure> =
            try {
                val state = canonical(System.getProperty("user.home")).parent
                if (state == null) Refinement.Rejected(HeadlessHostFailure.INVALID_PATH)
                else
                    Refinement.Refined(
                        HeadlessHostEnvironment(
                            state = state,
                            installation = canonical(PathManager.getHomePath()),
                            javaHome = canonical(System.getProperty("java.home")),
                            home = canonical(System.getProperty("user.home")),
                            config = canonical(PathManager.getConfigPath()),
                            system = canonical(PathManager.getSystemPath()),
                            plugins = canonical(PathManager.getPluginsPath()),
                            log = canonical(PathManager.getLogPath()),
                            temporary = canonical(System.getProperty("java.io.tmpdir")),
                            productionBootstrap =
                                ApplicationManager.getApplication().isHeadlessEnvironment &&
                                    !ApplicationManager.getApplication().isUnitTestMode &&
                                    System.getProperty("idea.force.use.core.classloader") in setOf(null, "false") &&
                                    System.getProperty("idea.trust.headless.disabled") == "false" &&
                                    System.getProperty("idea.trust.disabled") in setOf(null, "false") &&
                                    System.getProperty("idea.trust.all.projects") in setOf(null, "false") &&
                                    System.getProperty("idea.auto.reload.plugins") in setOf(null, "false"),
                        )
                    )
            } catch (_: java.io.IOException) {
                Refinement.Rejected(HeadlessHostFailure.INVALID_PATH)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(HeadlessHostFailure.INVALID_PATH)
            }

        private fun canonical(raw: String): Path {
            val path = Path.of(raw)
            require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path)
            return path
        }
    }
}

/** Production plugin loading selects this adapter through the vendor appStarter extension. */
class KastHeadlessStarter : ModernApplicationStarter() {
    override suspend fun start(args: List<String>) {
        when (val admission = ManagedHeadlessHost.start()) {
            is Refinement.Rejected -> {
                recordManagedHeadless(ManagedHeadlessObservation.StartupRejected(admission.failure))
                kotlin.system.exitProcess(2)
            }
            is Refinement.Refined -> {
                recordManagedHeadless(ManagedHeadlessObservation.StartupAdmitted)
                ApplicationManager.getApplication().getService(IdeLifecycleApplication::class.java)
            }
        }
    }
}
