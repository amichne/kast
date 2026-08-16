package io.github.amichne.kast.indexer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (val bootstrap = KastIndexerBootstrap.configureSystemProperties(args)) {
        is IndexerBootstrapConfiguration.Configured -> {
            val ideaMain = Class.forName("com.intellij.idea.Main")
                .getMethod("main", Array<String>::class.java)
            ideaMain.invoke(null, bootstrap.ideaArguments.toTypedArray())
        }
        is IndexerBootstrapConfiguration.Rejected -> {
            System.err.println("kast-indexer: invalid bootstrap: ${bootstrap.failure.name}")
            exitProcess(64)
        }
    }
}

enum class IndexerBootstrapFailure {
    MISSING_IDEA_HOME,
    DUPLICATE_IDEA_HOME,
    INVALID_IDEA_HOME,
}

sealed interface IndexerBootstrapConfiguration {
    data class Configured(
        val ideaArguments: List<String>,
    ) : IndexerBootstrapConfiguration

    data class Rejected(
        val failure: IndexerBootstrapFailure,
    ) : IndexerBootstrapConfiguration
}

internal object KastIndexerBootstrap {
    private const val IDEA_HOME_PREFIX = "--idea-home="

    /**
     * Proof transition: `Array<String> -> IndexerBootstrapConfiguration`.
     *
     * Establishes one canonical physical IDEA home and the headless platform properties required
     * by the installed launcher. [IndexerBootstrapFailure] is the closed expected failure. Raw
     * property and path extraction is permitted only at this outer JVM bootstrap boundary.
     */
    fun configureSystemProperties(args: Array<String>): IndexerBootstrapConfiguration {
        val ideaHome = when (val admission = args.ideaHome()) {
            is IdeaHomeAdmission.Admitted -> admission.path
            is IdeaHomeAdmission.Rejected -> return IndexerBootstrapConfiguration.Rejected(
                admission.failure,
            )
        }
        System.setProperty("java.awt.headless", "true")
        System.setProperty("idea.is.internal", "true")
        System.setProperty("idea.home.path", ideaHome.toString())
        return IndexerBootstrapConfiguration.Configured(ideaMainArgs(args))
    }

    /**
     * Boundary projection: `Array<String> -> List<String>`.
     *
     * Removes only the launcher-owned IDEA home option and prepends the registered application
     * command. Raw arguments remain inside the launcher-to-IDEA boundary.
     */
    fun ideaMainArgs(args: Array<String>): List<String> =
        listOf(
            KAST_INDEXER_COMMAND_NAME,
            *args.filterNot { it.startsWith(IDEA_HOME_PREFIX) }.toTypedArray(),
        )

    /**
     * Proof transition: `Array<String> -> IdeaHomeAdmission`.
     *
     * Establishes exactly one absolute, normalized, physically canonical IDEA home directory.
     * [IndexerBootstrapFailure] is the closed expected failure. The raw path may leave only for
     * JDK filesystem admission and the `idea.home.path` platform property.
     */
    private fun Array<String>.ideaHome(): IdeaHomeAdmission {
        val values = filter { it.startsWith(IDEA_HOME_PREFIX) }
            .map { it.removePrefix(IDEA_HOME_PREFIX) }
        if (values.isEmpty()) {
            return IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.MISSING_IDEA_HOME)
        }
        if (values.size != 1) {
            return IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.DUPLICATE_IDEA_HOME)
        }
        val candidate = try {
            Path(values.single())
        } catch (_: RuntimeException) {
            return IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.INVALID_IDEA_HOME)
        }
        if (!candidate.isAbsolute || candidate.normalize() != candidate) {
            return IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.INVALID_IDEA_HOME)
        }
        val canonical = try {
            candidate.toRealPath()
        } catch (_: IOException) {
            return IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.INVALID_IDEA_HOME)
        } catch (_: SecurityException) {
            return IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.INVALID_IDEA_HOME)
        }
        return if (
            canonical == candidate && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
        ) {
            IdeaHomeAdmission.Admitted(canonical)
        } else {
            IdeaHomeAdmission.Rejected(IndexerBootstrapFailure.INVALID_IDEA_HOME)
        }
    }
}

private sealed interface IdeaHomeAdmission {
    data class Admitted(
        val path: Path,
    ) : IdeaHomeAdmission

    data class Rejected(
        val failure: IndexerBootstrapFailure,
    ) : IdeaHomeAdmission
}
