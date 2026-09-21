import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

plugins {
    application
    id("kast.kotlin-library")
    id("com.gradleup.shadow")
}

val applicationName = project.name

val buildVersion: Provider<String> = providers.provider { project.version.toString() }

extra["buildVersion"] = buildVersion

val includeShadowJarProperty = providers.gradleProperty("kastIncludeShadowJar")
val includeShadowJar: Provider<Boolean> = providers.provider {
    val rawValue = includeShadowJarProperty.orNull ?: findProperty("kastIncludeShadowJar")?.toString()
    when (val normalized = rawValue?.trim()?.lowercase()) {
        null, "" -> true
        "true" -> true
        "false" -> false
        else -> error("kastIncludeShadowJar must be true or false, got $normalized")
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = applicationName
        attributes["Implementation-Version"] = buildVersion.get()
    }
    isZip64 = true
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = applicationName
        attributes["Implementation-Version"] = buildVersion.get()
    }
    isZip64 = true
}

val shadowJarArchive = shadowJar.flatMap(ShadowJar::getArchiveFile)

val mainJar = tasks.named<Jar>("jar")

val syncRuntimeLibs = tasks.register<SyncRuntimeLibsTask>("syncRuntimeLibs") {
    dependsOn(mainJar)
    appJar.set(mainJar.flatMap(Jar::getArchiveFile))
    runtimeJars.from(configurations.runtimeClasspath)
    outputDirectory.set(layout.buildDirectory.dir("runtime-libs"))
    classpathFile.set(layout.buildDirectory.file("runtime-libs/classpath.txt"))
}

val writeWrapperScript = tasks.register<WriteWrapperScriptTask>("writeWrapperScript") {
    dependsOn(syncRuntimeLibs)

    jarFileName.set(shadowJar.flatMap(ShadowJar::getArchiveFileName))
    outputFile.set(layout.buildDirectory.file("scripts/$applicationName"))
}

val syncPortableDist = tasks.register<Sync>("syncPortableDist") {
    dependsOn(writeWrapperScript)
    into(layout.buildDirectory.dir("portable-dist/$applicationName"))
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(writeWrapperScript)
    // runtime-libs is intentionally absent here: consumers must explicitly wire their
    // own runtime-libs source so that classpath.txt references the correct application jars.
}

afterEvaluate {
    if (includeShadowJar.get()) {
        writeWrapperScript.configure {
            dependsOn(shadowJar)
        }
        syncPortableDist.configure {
            from(shadowJarArchive) {
                into("libs")
            }
        }
    }
}

val portableDistZip = tasks.register<Zip>("portableDistZip") {
    val archiveRoot = applicationName
    dependsOn(syncPortableDist)
    archiveBaseName.set(applicationName)
    archiveClassifier.set("portable")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    dirPermissions { unix("755") }
    filePermissions { unix("644") }

    from(syncPortableDist) {
        into(archiveRoot)
    }

    eachFile {
        val archivePath = relativePath.pathString
        if (archivePath == "$archiveRoot/$archiveRoot" || archivePath.startsWith("$archiveRoot/bin/")) {
            permissions { unix("755") }
        }
    }
}

writeWrapperScript.configure {
    // Both tasks write build/scripts; the Kast launcher must replace Gradle's default Unix script.
    mustRunAfter(tasks.named("startScripts"))
}

tasks.matching {
    it.name in setOf("distZip", "distTar", "installDist")
}.configureEach {
    dependsOn(writeWrapperScript)
}
