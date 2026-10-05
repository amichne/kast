package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Case-owned selected IDE/JBR facts for launch-policy tests; no platform process is executed. */
fun selectedBrokerJbr(root: Path): Pair<String, String> {
    val idea = Files.createDirectories(root.resolve("selected IDEA/Contents")).toRealPath()
    val javaHome = Files.createDirectories(idea.resolve("jbr/Contents/Home"))
    val java = Files.createDirectories(javaHome.resolve("bin")).resolve("java")
    if (!Files.exists(java)) {
        Files.writeString(java, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"))
    }
    Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n")
    return "KAST_INSTALL_IDEA_HOME" to idea.toString()
}
