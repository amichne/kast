package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.publicshell.PublicShellExit
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The installed launcher retains the selected IDEA JBR; ambient Java never supplies its fallback. */
internal fun installationJavaRuntimeEnvironment(
    home: InstallationPath,
    failureMode: InstallationJavaFailureMode = InstallationJavaFailureMode.PRIVATE_LAUNCHER,
): String {
    val quoted = "'${home.value.toString().replace("'", "'\"'\"'")}'"
    val failure = installationJavaFailure(failureMode)
    return """
        kast_java_home=$quoted
        kast_java_failure() { $failure; }
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

private fun installationJavaFailure(mode: InstallationJavaFailureMode): String =
    when (mode) {
        InstallationJavaFailureMode.PRIVATE_LAUNCHER ->
            "printf '%s\\n' 'kast: selected IDEA JBR requires Java 25 or newer; repair the selected IDEA installation' >&2; exit 1"
        InstallationJavaFailureMode.PUBLIC_QUERY -> {
            val document = Json.encodeToString<ToolRpcReply>(ToolRpcReply.Rejected(ToolRpcFailure.CATALOG_UNAVAILABLE))
            val quoted = "'${document.replace("'", "'\"'\"'")}'"
            "trap '' PIPE; printf '%s\\n' $quoted || exit ${PublicShellExit.IO.code}; exit ${PublicShellExit.RUNTIME.code}"
        }
    }
