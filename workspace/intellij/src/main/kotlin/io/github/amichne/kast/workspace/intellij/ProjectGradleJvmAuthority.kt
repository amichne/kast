package io.github.amichne.kast.workspace.intellij

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Properties

internal sealed interface ProjectGradleJvmAuthority {
    sealed interface Admitted : ProjectGradleJvmAuthority

    data object Absent : Admitted

    class Present private constructor(val home: Path) : Admitted {
        companion object {
            /** Establishes physical executable JDK-home authority before IntelliJ is entered. */
            fun admit(home: Path): ProjectGradleJvmAuthority =
                if (Files.isDirectory(home) && Files.isRegularFile(home.resolve("bin/java")) &&
                    Files.isExecutable(home.resolve("bin/java"))
                ) Present(home) else Rejected
        }
    }

    data object Rejected : ProjectGradleJvmAuthority
}

/** Optional inherited Java-home evidence, never promoted to repository authority. */
internal sealed interface AmbientGradleJvmAuthority {
    data object Absent : AmbientGradleJvmAuthority

    class Present private constructor(val home: Path) : AmbientGradleJvmAuthority {
        companion object {
            fun admit(home: Path): AmbientGradleJvmAuthority =
                if (Files.isDirectory(home) && Files.isRegularFile(home.resolve("bin/java")) &&
                    Files.isExecutable(home.resolve("bin/java"))
                ) Present(home) else Rejected
        }
    }

    /** Invalid ambient input is retained as rejected evidence but does not mandate a JVM. */
    data object Rejected : AmbientGradleJvmAuthority
}

/** Reads only the repository-owned property; user and installation Gradle properties stay out. */
internal fun projectGradleJvmAuthority(root: Path): ProjectGradleJvmAuthority {
    val propertiesFile = root.resolve("gradle.properties")
    if (Files.notExists(propertiesFile)) return ProjectGradleJvmAuthority.Absent
    if (!Files.isRegularFile(propertiesFile) || Files.isSymbolicLink(propertiesFile)) {
        return ProjectGradleJvmAuthority.Rejected
    }
    if (try { Files.size(propertiesFile) > 1_048_576L } catch (_: IOException) { true } catch (_: SecurityException) { true }) {
        return ProjectGradleJvmAuthority.Rejected
    }
    val properties = Properties()
    try {
        Files.newBufferedReader(propertiesFile).use(properties::load)
    } catch (_: IOException) {
        return ProjectGradleJvmAuthority.Rejected
    } catch (_: SecurityException) {
        return ProjectGradleJvmAuthority.Rejected
    } catch (_: IllegalArgumentException) {
        return ProjectGradleJvmAuthority.Rejected
    }
    val raw = properties.getProperty("org.gradle.java.home") ?: return ProjectGradleJvmAuthority.Absent
    if (raw.isBlank()) return ProjectGradleJvmAuthority.Rejected
    val candidate = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return ProjectGradleJvmAuthority.Rejected
    }
    if (!candidate.isAbsolute || candidate.normalize() != candidate) {
        return ProjectGradleJvmAuthority.Rejected
    }
    val canonical = try {
        candidate.toRealPath()
    } catch (_: IOException) {
        return ProjectGradleJvmAuthority.Rejected
    } catch (_: SecurityException) {
        return ProjectGradleJvmAuthority.Rejected
    }
    return ProjectGradleJvmAuthority.Present.admit(canonical)
}

/** Refines the optional inherited Java home without making absence or rejection fatal. */
internal fun ambientGradleJvmAuthority(raw: String?): AmbientGradleJvmAuthority {
    if (raw == null) return AmbientGradleJvmAuthority.Absent
    if (raw.isBlank()) return AmbientGradleJvmAuthority.Rejected
    val candidate = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return AmbientGradleJvmAuthority.Rejected
    }
    if (!candidate.isAbsolute || candidate.normalize() != candidate) {
        return AmbientGradleJvmAuthority.Rejected
    }
    val canonical = try {
        candidate.toRealPath()
    } catch (_: IOException) {
        return AmbientGradleJvmAuthority.Rejected
    } catch (_: SecurityException) {
        return AmbientGradleJvmAuthority.Rejected
    }
    return AmbientGradleJvmAuthority.Present.admit(canonical)
}
