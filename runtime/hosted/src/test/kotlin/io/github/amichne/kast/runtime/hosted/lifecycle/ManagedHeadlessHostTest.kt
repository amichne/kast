package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManagedHeadlessHostTest {
    private fun environment(): HeadlessHostEnvironment {
        val state = Path.of("/owned/state")
        val installation = Path.of("/owned/installation")
        return HeadlessHostEnvironment(
            state,
            installation,
            installation.resolve("jbr"),
            state.resolve("home"),
            state.resolve("home/.local/share/JetBrains/IntelliJIdea2026.2"),
            state.resolve("system"),
            state.resolve("home/.local/share/JetBrains/IntelliJIdea2026.2/plugins"),
            state.resolve("log"),
            state.resolve("tmp"),
            productionBootstrap = true,
        )
    }

    @Test
    fun `private production paths preserve the admitted ownership`() {
        val environment = environment()
        val admitted = assertInstanceOf(Refinement.Refined::class.java, ManagedHeadlessHost.admit(environment))
        assertEquals(environment, (admitted.value as ManagedHeadlessHost).environment)
    }

    @Test
    fun `the native installer profile stays inside the private user home`() {
        val environment = environment()
        for (profile in
            listOf(
                ".local/share/JetBrains/IntelliJIdea2026.2/plugins",
                "Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins",
            )) {
            assertInstanceOf(
                Refinement.Refined::class.java,
                ManagedHeadlessHost.admit(
                    environment.copy(
                        plugins = environment.home.resolve(profile),
                        config = environment.home.resolve(profile).parent,
                    )
                ),
            )
        }
    }

    @Test
    fun `each desktop state path rejects instead of entering the lifecycle`() {
        val environment = environment()
        val desktop = Path.of("/developer/idea")
        listOf(
                environment.copy(home = desktop),
                environment.copy(config = desktop),
                environment.copy(system = desktop),
                environment.copy(plugins = desktop),
                environment.copy(log = desktop),
                environment.copy(temporary = desktop),
                environment.copy(installation = environment.state),
            )
            .forEach {
                assertEquals(Refinement.Rejected(HeadlessHostFailure.SHARED_STATE), ManagedHeadlessHost.admit(it))
            }
    }

    @Test
    fun `a borrowed JBR and fixture bootstrap reject`() {
        val environment = environment()
        assertEquals(
            Refinement.Rejected(HeadlessHostFailure.FOREIGN_JBR),
            ManagedHeadlessHost.admit(environment.copy(javaHome = Path.of("/developer/idea/jbr"))),
        )
        assertEquals(
            Refinement.Rejected(HeadlessHostFailure.UNSAFE_BOOTSTRAP),
            ManagedHeadlessHost.admit(environment.copy(productionBootstrap = false)),
        )
    }

    @Test
    fun `relative and unnormalized paths reject`() {
        val environment = environment()
        for (path in listOf(Path.of("relative"), Path.of("/owned/state/../state"))) {
            assertEquals(
                Refinement.Rejected(HeadlessHostFailure.INVALID_PATH),
                ManagedHeadlessHost.admit(environment.copy(state = path)),
            )
        }
    }

    @Test
    fun `native metadata uses a private deterministic store without changing the repository identity`() {
        val host = (ManagedHeadlessHost.admit(environment()) as Refinement.Refined).value
        assertEquals(
            Path.of("/owned/state/system/kast-projects/c52ddf65534b7b46035084358ab7902b"),
            host.projectStore(Path.of("/workspace")),
        )
        for (excluded in listOf("/", "/owned", "/owned/state", "/owned/state/system", "/owned/installation")) {
            assertNull(host.projectStore(Path.of(excluded)))
        }
    }
}
