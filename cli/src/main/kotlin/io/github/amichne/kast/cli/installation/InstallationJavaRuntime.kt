package io.github.amichne.kast.cli.installation

/** The installed launcher retains the selected IDEA JBR; ambient Java never supplies its fallback. */
internal fun installationJavaRuntimeEnvironment(home: InstallationPath): String {
    val quoted = "'${home.value.toString().replace("'", "'\"'\"'")}'"
    return """
        kast_java_home=$quoted
        kast_java_failure() { printf '%s\n' 'kast: selected IDEA JBR requires Java 25 or newer; repair the selected IDEA installation' >&2; exit 1; }
        [ -x "${'$'}kast_java_home/bin/java" ] && [ -f "${'$'}kast_java_home/release" ] || kast_java_failure
        kast_java_feature=${'$'}(/usr/bin/awk '
            /^JAVA_VERSION=/ {
                declarations++
                if (${'$'}0 ~ /^JAVA_VERSION="[0-9]+[^"\r]*"\r?$/) {
                    feature = ${'$'}0
                    sub(/^JAVA_VERSION="/, "", feature)
                    sub(/[^0-9].*$/, "", feature)
                }
            }
            END { if (declarations == 1 && feature != "") print feature }
        ' "${'$'}kast_java_home/release")
        case "${'$'}kast_java_feature" in ''|*[!0-9]*) kast_java_failure ;; esac
        [ "${'$'}kast_java_feature" -ge 25 ] || kast_java_failure
        export JAVA_HOME="${'$'}kast_java_home"
        export JAVA="${'$'}kast_java_home/bin/java"
    """
        .trimIndent()
}
