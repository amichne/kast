import org.gradle.jvm.tasks.Jar

plugins {
    id("kast.standalone-serialization-app")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellijIdeaVersion = catalog.findVersion("intellij-idea").get().requiredVersion

val ideaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/intellij-distributions/$intellijIdeaVersion")))
}

val extractIdeaDistribution: TaskProvider<ExtractIdeaDistributionTask> by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(ideaDistribution)
    ideaVersion.set(intellijIdeaVersion)
    outputDirectory.set(extractedIdeaDistributionDirectory)
}

private fun extractedIdeaFiles(
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            configure()
        }
    },
).builtBy(extractIdeaDistribution)

val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/testFramework.jar")
    exclude("**/testFramework-k1.jar")
}

val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

val headlessPluginRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.slf4j", module = "slf4j-api")
}

application {
    mainClass = "io.github.amichne.kast.headless.HeadlessMainKt"
}

@Suppress("UNCHECKED_CAST")
val buildVersion: Provider<String> = extra["buildVersion"] as Provider<String>

val writeBackendVersion by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated-resources/kast-backend-version.txt")
    val versionProvider = buildVersion
    inputs.property("buildVersion", versionProvider)
    outputs.file(versionFile)
    doLast {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(versionProvider.get())
        }
    }
}

sourceSets.main {
    resources.srcDir(writeBackendVersion.map { it.outputs.files.singleFile.parentFile })
}

dependencies {
    ideaDistribution("com.jetbrains.intellij.idea:ideaIC:$intellijIdeaVersion@zip") {
        isTransitive = false
    }

    compileOnly(project(":analysis-api"))
    compileOnly(project(":analysis-server"))
    compileOnly(project(":backend-intellij"))
    compileOnly(project(":backend-shared"))
    compileOnly(project(":index-store"))
    implementation(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
    compileOnly(libs.coroutines.core)

    headlessPluginRuntime(project(":analysis-api"))
    headlessPluginRuntime(project(":analysis-server"))
    headlessPluginRuntime(project(":backend-intellij"))
    headlessPluginRuntime(project(":backend-shared"))
    headlessPluginRuntime(project(":index-store"))
    headlessPluginRuntime(libs.coroutines.core)

    testImplementation(project(":analysis-api"))
    testImplementation(project(":backend-intellij"))
}

tasks.named<WriteWrapperScriptTask>("writeWrapperScript") {
    outputFile.set(layout.buildDirectory.file("scripts/kast-headless"))
    val dollar = "$"
    scriptContent.set(
        """
        #!/usr/bin/env bash
        set -euo pipefail

        script_dir="$(cd -- "$(dirname -- "${dollar}{BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
        main_class="io.github.amichne.kast.headless.HeadlessMainKt"
        runtime_libs_dir="${dollar}{script_dir}/runtime-libs"

        if [[ ! -d "${dollar}{runtime_libs_dir}" ]]; then
          echo "kast-headless: runtime-libs directory not found: ${dollar}{runtime_libs_dir}" >&2
          echo "hint: reinstall with kast.sh to restore the packaged runtime libraries" >&2
          exit 1
        fi

        classpath_file="${dollar}{runtime_libs_dir}/classpath.txt"
        if [[ ! -f "${dollar}{classpath_file}" ]]; then
          echo "kast-headless: classpath.txt not found in ${dollar}{runtime_libs_dir}" >&2
          exit 1
        fi

        classpath=""
        while IFS= read -r jar; do
          [[ -z "${dollar}{jar}" ]] && continue
          if [[ -z "${dollar}{classpath}" ]]; then
            classpath="${dollar}{runtime_libs_dir}/${dollar}{jar}"
          else
            classpath="${dollar}{classpath}:${dollar}{runtime_libs_dir}/${dollar}{jar}"
          fi
        done < "${dollar}{classpath_file}"

        if [[ -z "${dollar}{classpath}" ]]; then
          echo "kast-headless: classpath.txt is empty in ${dollar}{runtime_libs_dir}" >&2
          exit 1
        fi

        java_exec="${dollar}{JAVA_HOME:+${dollar}{JAVA_HOME}/bin/java}"
        java_exec="${dollar}{java_exec:-java}"

        idea_home=""
        for arg in "$@"; do
          case "${dollar}{arg}" in
            --idea-home=*) idea_home="${dollar}{arg#--idea-home=}" ;;
          esac
        done

        idea_jvm_args=()
        if [[ -n "${dollar}{idea_home}" ]]; then
          jna_arch="amd64"
          case "$(uname -m)" in
            arm64|aarch64) jna_arch="aarch64" ;;
          esac
          idea_jvm_args=(
            "-Xbootclasspath/a:${dollar}{idea_home}/lib/nio-fs.jar"
            "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader"
            "-Didea.vendor.name=JetBrains"
            "-Didea.paths.selector=KastHeadless"
            "-Didea.plugins.path=${dollar}{script_dir}/plugins"
            "-Djna.boot.library.path=${dollar}{idea_home}/lib/jna/${dollar}{jna_arch}"
            "-Djna.nosys=true"
            "-Djna.noclasspath=true"
            "-Dpty4j.preferred.native.folder=${dollar}{idea_home}/lib/pty4j"
            "-Dio.netty.allocator.type=pooled"
            "-Dintellij.platform.runtime.repository.path=${dollar}{idea_home}/modules/module-descriptors.dat"
            "-Didea.platform.prefix=Idea"
            "-Dsplash=false"
            "-Daether.connector.resumeDownloads=false"
            "-Dcompose.swing.render.on.graphics=true"
            "--add-opens=java.base/java.io=ALL-UNNAMED"
            "--add-opens=java.base/java.lang=ALL-UNNAMED"
            "--add-opens=java.base/java.lang.ref=ALL-UNNAMED"
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
            "--add-opens=java.base/java.net=ALL-UNNAMED"
            "--add-opens=java.base/java.nio=ALL-UNNAMED"
            "--add-opens=java.base/java.nio.charset=ALL-UNNAMED"
            "--add-opens=java.base/java.text=ALL-UNNAMED"
            "--add-opens=java.base/java.time=ALL-UNNAMED"
            "--add-opens=java.base/java.util=ALL-UNNAMED"
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
            "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED"
            "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
            "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED"
            "--add-opens=java.base/sun.net.dns=ALL-UNNAMED"
            "--add-opens=java.base/sun.nio=ALL-UNNAMED"
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
            "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED"
            "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED"
            "--add-opens=java.base/sun.security.util=ALL-UNNAMED"
            "--add-opens=java.desktop/com.sun.java.swing=ALL-UNNAMED"
            "--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED"
            "--add-exports=java.desktop/com.apple.laf=ALL-UNNAMED"
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED"
            "--add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED"
            "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED"
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
            "--add-opens=java.desktop/java.awt.image=ALL-UNNAMED"
            "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED"
            "--add-opens=java.desktop/javax.swing=ALL-UNNAMED"
            "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED"
            "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED"
            "--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED"
            "--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.awt.image=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.font=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED"
            "--add-opens=java.desktop/sun.swing=ALL-UNNAMED"
            "--add-opens=java.management/sun.management=ALL-UNNAMED"
            "--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED"
            "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED"
            "--add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED"
          )
        fi

        exec "${dollar}{java_exec}" "${dollar}{idea_jvm_args[@]}" ${dollar}{JAVA_OPTS:-} -cp "${dollar}{classpath}" "${dollar}{main_class}" "$@"
        """.trimIndent(),
    )
}

tasks.named<SyncRuntimeLibsTask>("syncRuntimeLibs") {
    requiredClassEntries.add("io/github/amichne/kast/headless/HeadlessMainKt.class")
    requiredClassEntries.add("com/intellij/idea/Main.class")
    requiredClassEntries.add("com/intellij/openapi/application/ModernApplicationStarter.class")
    requiredClassEntries.add("com/intellij/openapi/project/DumbService.class")
}

tasks.named<Sync>("syncPortableDist") {
    from(layout.buildDirectory.dir("runtime-libs")) {
        into("runtime-libs")
    }
    from(tasks.named<Jar>("jar")) {
        into("plugins/kast-headless/lib")
    }
    from(headlessPluginRuntime) {
        into("plugins/kast-headless/lib")
    }
    dependsOn("syncRuntimeLibs")
}

tasks.named<Zip>("portableDistZip") {
    eachFile {
        if (relativePath.pathString == "backend-headless/kast-headless") {
            permissions { unix("755") }
        }
    }
}
