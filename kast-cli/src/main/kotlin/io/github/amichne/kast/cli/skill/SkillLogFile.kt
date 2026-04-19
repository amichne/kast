package io.github.amichne.kast.cli.skill

/**
 * Manages wrapper-level log file paths.
 * In the shell wrappers, each invocation produces a combined log file.
 * The Kotlin wrappers will do the same once full log aggregation is wired;
 * for now, a placeholder path is used.
 */
internal object SkillLogFile {

    // TODO: implement real log file aggregation matching shell wrapper behavior
    fun placeholder(): String = "/dev/null"
}
