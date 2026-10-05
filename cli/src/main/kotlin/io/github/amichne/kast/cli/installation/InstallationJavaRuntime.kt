package io.github.amichne.kast.cli.installation

/** The installed launcher retains the selected IDEA JBR; ambient Java never supplies its fallback. */
internal fun installationJavaRuntimeEnvironment(home: InstallationPath): String {
    val quoted = "'${home.value.toString().replace("'", "'\"'\"'")}'"
    return """
        kast_java_home=$quoted
        kast_java_failure() { printf '%s\n' 'kast: selected IDEA JBR requires Java 25 or newer; repair the selected IDEA installation' >&2; exit 1; }
        [ -x "${'$'}kast_java_home/bin/java" ] && [ -f "${'$'}kast_java_home/release" ] || kast_java_failure
        kast_java_feature=${'$'}(/usr/bin/sed -nE 's/^JAVA_VERSION="([0-9]+).*/\1/p' "${'$'}kast_java_home/release" | /usr/bin/awk 'NR == 1 { print; exit }')
        case "${'$'}kast_java_feature" in ''|*[!0-9]*) kast_java_failure ;; esac
        [ "${'$'}kast_java_feature" -ge 25 ] || kast_java_failure
        export JAVA_HOME="${'$'}kast_java_home"
        export JAVA="${'$'}kast_java_home/bin/java"
    """
        .trimIndent()
}
