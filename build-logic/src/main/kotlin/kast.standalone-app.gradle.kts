import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

plugins {
    application
    id("kast.kotlin-library")
}

val cleanPackagingOutputs by tasks.registering(Delete::class) {
    group = "build"
    description = "Removes generated packaging outputs before rebuilding ${project.name}."

    delete(
        layout.buildDirectory.dir("distributions"),
        layout.buildDirectory.dir("install"),
        layout.buildDirectory.dir("libs"),
        layout.buildDirectory.dir("portable-dist"),
        layout.buildDirectory.dir("scripts"),
    )
}

tasks.named<Jar>("jar") {
    dependsOn(cleanPackagingOutputs)
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = project.version.toString()
    }
    isZip64 = true
}

val fatJar by tasks.registering(Jar::class) {
    dependsOn(cleanPackagingOutputs)
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
    dirPermissions { unix("755") }
    filePermissions { unix("644") }

    from(syncPortableDist) {
        into(project.name)
    }

    eachFile {
        val archivePath = relativePath.pathString
        if (archivePath == "${project.name}/${project.name}" || archivePath.startsWith("${project.name}/bin/")) {
            permissions { unix("755") }
        }
    }
}

writeWrapperScript.configure {
    mustRunAfter(tasks.named("startScripts"))
}

tasks.matching {
    it.name == "sourcesJar"
}.configureEach {
    dependsOn(cleanPackagingOutputs)
}

tasks.named("startScripts").configure {
    dependsOn(cleanPackagingOutputs)
}

tasks.matching {
    it.name in setOf("distZip", "distTar", "installDist")
}.configureEach {
    dependsOn(writeWrapperScript)
}
