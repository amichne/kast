import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

plugins {
    application
    id("kas.kotlin-library")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = project.version.toString()
    }
    isZip64 = true
}

val fatJar by tasks.registering(Jar::class) {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath.get().buildDependencies)

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = project.version.toString()
    }
    isZip64 = true

    from(sourceSets.main.get().output)

    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

val fatJarArchive = fatJar.flatMap { it.archiveFile }

val runtimeClassPathJars = configurations.runtimeClasspath.map { classpath ->
    classpath
        .filter { file -> file.name.endsWith(".jar") }
        .map { file -> file.absolutePath }
}

val syncRuntimeLibs by tasks.registering(SyncRuntimeLibsTask::class) {
    dependsOn(tasks.named<Jar>("jar"))
    appJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeJars.from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
    })
    runtimeJarPathsInOrder.set(runtimeClassPathJars)
    outputDirectory.set(layout.buildDirectory.dir("runtime-libs"))
    classpathFile.set(layout.buildDirectory.file("runtime-libs/classpath.txt"))
}

val writeWrapperScript by tasks.registering(WriteWrapperScriptTask::class) {
    dependsOn(fatJar)
    dependsOn(syncRuntimeLibs)

    jarFileName.set(fatJarArchive.map { it.asFile.name })
    outputFile.set(layout.buildDirectory.file("scripts/${project.name}"))
}

val syncPortableDist by tasks.registering(Sync::class) {
    dependsOn(writeWrapperScript)
    into(layout.buildDirectory.dir("portable-dist/${project.name}"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(writeWrapperScript)
    from(fatJarArchive) {
        into("libs")
    }
    from(syncRuntimeLibs) {
        into("runtime-libs")
    }
}

val portableDistZip by tasks.registering(Zip::class) {
    dependsOn(syncPortableDist)
    archiveBaseName.set(project.name)
    archiveClassifier.set("portable")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(syncPortableDist) {
        into(project.name)
    }
}

writeWrapperScript.configure {
    mustRunAfter(tasks.named("startScripts"))
}

tasks.matching {
    it.name in setOf("distZip", "distTar", "installDist")
}.configureEach {
    dependsOn(writeWrapperScript)
}
