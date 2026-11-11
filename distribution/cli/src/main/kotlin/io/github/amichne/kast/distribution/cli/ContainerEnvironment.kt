package io.github.amichne.kast.distribution.cli

/** Installation selectors and JVM injection never cross into a private workspace. */
internal fun containerEnvironment(container: AdmittedContainer, inherited: Map<String, String>): Map<String, String> {
    val state = container.state
    val home = container.home
    val java =
        container.runtime.home.resolve(
            if (container.runtime.manifest.platform.publicName.startsWith("macos")) "jbr/Contents/Home" else "jbr"
        )
    val retained = inherited.filterKeys { key ->
        !key.startsWith("KAST_") &&
            !key.startsWith("IDEA_") &&
            !key.startsWith("XDG_") &&
            !key.startsWith("DYLD_") &&
            !key.startsWith("PYTHON") &&
            key !in
                setOf(
                    "BASH_ENV",
                    "ENV",
                    "CDPATH",
                    "JAVA",
                    "JDK_HOME",
                    "JAVA_TOOL_OPTIONS",
                    "_JAVA_OPTIONS",
                    "JDK_JAVA_OPTIONS",
                    "GRADLE_OPTS",
                    "CODEX_HOME",
                    "LD_LIBRARY_PATH",
                    "LD_PRELOAD",
                )
    }
    return retained +
        inherited.filterKeys { it == "KAST_OUTPUT_TOON" } +
        mapOf(
            "HOME" to home.toString(),
            "JAVA_HOME" to java.toString(),
            "TMPDIR" to state.resolve("tmp").toString(),
            "XDG_CONFIG_HOME" to home.resolve(".config").toString(),
            "XDG_DATA_HOME" to home.resolve(".local/share").toString(),
            "XDG_CACHE_HOME" to home.resolve(".cache").toString(),
            "XDG_STATE_HOME" to home.resolve(".local/state").toString(),
            "XDG_RUNTIME_DIR" to state.resolve("tmp").toString(),
            "CODEX_HOME" to home.resolve(".codex").toString(),
            "GRADLE_USER_HOME" to container.gradleHome.toString(),
            "KAST_CONFIGURATION_FILE" to container.configuration.toString(),
            "KAST_MANAGED_HEADLESS_STATE" to state.toString(),
            "PATH" to (java.resolve("bin").toString() + ":" + (inherited["PATH"] ?: "/usr/bin:/bin")),
        )
}
