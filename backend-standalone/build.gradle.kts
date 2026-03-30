plugins {
    id("kas.standalone-app")
}

// Pinned to IJ 2025.3 (ij253) — must match the analysis-api-standalone-for-ide version.
// If the distribution is absent, run: ./gradlew :backend-intellij:build
val ideaHomeOrNull: File? = fileTree(gradle.gradleUserHomeDir.resolve("caches/9.0.0/transforms")) {
    include("**/transformed/idea-2025.3-*/plugins/Kotlin/lib/kotlin-plugin.jar")
}.files.firstOrNull()?.parentFile?.parentFile?.parentFile?.parentFile

val ideaHome: File get() = ideaHomeOrNull
    ?: error(
        "IntelliJ IDEA 2025.3 distribution not found in the Gradle cache. " +
            "Run './gradlew :backend-intellij:build' once to populate it.",
    )

// ───────────────────────────────────────────────────────────────────────────────
// Compat JAR strategy
//
// The AA (analysis-api-standalone-for-ide) was compiled against an early IJ 2025.3
// EAP. The stable IJ 2025.3 distribution has two sets of conflicting classes in
// the com.intellij.ide.plugins package:
//
//  ┌─────────────────────────┬─────────────────────────────────────────────────────────┐
//  │ Source                  │ Required for                                            │
//  ├─────────────────────────┼─────────────────────────────────────────────────────────┤
//  │ app.jar (IJ 2025.3)     │ PluginDescriptorLoader, IdeaPluginDescriptorImpl        │
//  │                         │ (abstract base for PluginMainDescriptor /               │
//  │                         │ PluginModuleDescriptor created by KotlinCoreEnvironment)│
//  ├─────────────────────────┼─────────────────────────────────────────────────────────┤
//  │ kotlin-compiler.jar     │ RawPluginDescriptor, ReadModuleContext, XmlReader,      │
//  │ (OLD, via compat JAR)   │ PluginXmlPathResolver, PathResolver (4-arg resolvePath) │
//  │                         │ — all used by PluginStructureProvider in the AA.        │
//  └─────────────────────────┴─────────────────────────────────────────────────────────┘
//
// ContainerDescriptor is special: IJ 2025.3's ParserElementsConversionKt calls the
// 4-arg constructor, while the AA calls getServices().  Neither old nor new provides
// both; we compile a hybrid that does.
//
// The compat JAR is placed FIRST on the classpath to win the class-loading race for
// the classes listed above that must come from kotlin-compiler.jar.
// ───────────────────────────────────────────────────────────────────────────────

// Compile compat bridge sources.
// ContainerDescriptor: JDK-only (raw List types).
// PathResolver / PluginXmlPathResolver: need app.jar (new API types) +
//   kotlin-compiler.jar (old RawPluginDescriptor, ReadModuleContext, XmlReader, StaxFactory).
val compileCompatJava by tasks.registering(JavaCompile::class) {
    source = fileTree("src/compat/java") { include("**/*.java") }
    classpath = if (ideaHomeOrNull != null) {
        files(
            ideaHome.resolve("lib/app.jar"),
            ideaHome.resolve("plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar"),
        )
    } else {
        files()
    }
    destinationDirectory.set(layout.buildDirectory.dir("compat-classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(listOf("-Xlint:-rawtypes", "-Xlint:-unchecked"))
}

val buildIdeCompatJar by tasks.registering(Jar::class) {
    onlyIf { ideaHomeOrNull != null }
    archiveFileName.set("ide-plugin-compat.jar")
    destinationDirectory.set(layout.buildDirectory.dir("compat"))
    // 1. Hybrid ContainerDescriptor must come first to win the duplicate race.
    from(compileCompatJava)
    from(
        zipTree(
            provider { ideaHome.resolve("plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar") },
        ),
    ) {
        // Include all plugin-descriptor parsing classes that the AA
        // (PluginStructureProvider) needs in their OLD (kotlin-compiler.jar) form.
        include("com/intellij/ide/plugins/**/*.class")
        // ListenerDescriptor moved out of the compiler copy in IJ 2025.3's runtime libs,
        // but the AA still writes the old mutable pluginDescriptor field.
        include("com/intellij/util/messages/ListenerDescriptor.class")
        // ContainerDescriptor — replaced by our hybrid above.
        exclude("com/intellij/ide/plugins/ContainerDescriptor.class")
        // IdeaPluginDescriptorImpl — must use the IJ 2025.3 app.jar version (abstract class)
        // so that PluginMainDescriptor / PluginModuleDescriptor can extend it.  The old version
        // is final and causes IncompatibleClassChangeError when those subclasses are loaded.
        exclude("com/intellij/ide/plugins/IdeaPluginDescriptorImpl.class")
        exclude("com/intellij/ide/plugins/IdeaPluginDescriptorImplKt.class")
        // PluginDescriptorLoader — must use the IJ 2025.3 app.jar version so that
        // CoreApplicationEnvironment.registerExtensionPointAndExtensions() creates the correct
        // PluginMainDescriptor (new API) rather than the old IdeaPluginDescriptorImpl.
        exclude("com/intellij/ide/plugins/PluginDescriptorLoader.class")
        exclude("com/intellij/ide/plugins/PluginDescriptorLoader\$loadForCoreEnv\$1.class")
        // NonShareableJavaZipFilePool — the new PluginDescriptorLoader immediately casts it to
        // ZipEntryResolverPool; the old version doesn't implement that interface → ClassCastException.
        exclude("com/intellij/ide/plugins/NonShareableJavaZipFilePool.class")
        exclude("com/intellij/ide/plugins/NonShareableJavaZipFilePool\$*.class")
        // ImmutableZipFileDataLoader — new PluginDescriptorLoader calls new constructor
        // (ZipEntryResolverPool$EntryResolver, Path); old version has different constructor signature.
        exclude("com/intellij/ide/plugins/ImmutableZipFileDataLoader.class")
        exclude("com/intellij/ide/plugins/ImmutableZipFileDataLoader\$*.class")
        // DataLoader — new version adds getEmptyDescriptorIfCannotResolve() as a default method;
        // new PluginDescriptorLoader calls it, which would fail with NoSuchMethodError on the old
        // interface. The new version is a superset: all old methods (load, toString,
        // isExcludedFromSubSearch) are still present.
        exclude("com/intellij/ide/plugins/DataLoader.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val ideaLibs = if (ideaHomeOrNull != null) {
    fileTree(ideaHome.resolve("lib")) {
        include("**/*.jar")
        // testFramework.jar auto-registers ThreadLeakTrackerExtension (JUnit 5 extension)
        // which needs --add-opens for javax.swing and is not needed for standalone analysis.
        exclude("testFramework.jar")
        exclude("testFramework-k1.jar")
    }
} else {
    files()
}
val kotlinPluginLibs = if (ideaHomeOrNull != null) {
    fileTree(ideaHome.resolve("plugins/Kotlin/lib")) {
        include("**/*.jar")
        // kotlin-jps-plugin.jar ships an old Java CompilerConfiguration (no Kotlin companion)
        // that shadows the correct version in kotlin-compiler-common.jar → NoSuchFieldError.
        exclude("jps/**")
    }
} else {
    files()
}
val javaPluginLibs = if (ideaHomeOrNull != null) {
    fileTree(ideaHome.resolve("plugins/java/lib")) { include("**/*.jar") }
} else {
    files()
}

application {
    mainClass = "io.github.amichne.kast.standalone.StandaloneMainKt"
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-common"))
    implementation(project(":analysis-server"))
    implementation(libs.analysis.api.standalone) {
        isTransitive = false
    }
    implementation(libs.gradle.tooling.api)
    // compat JAR FIRST — wins the class-loading race for old-API classes
    implementation(files(buildIdeCompatJar.map { it.archiveFile }))
    implementation(ideaLibs)
    implementation(kotlinPluginLibs)
    implementation(javaPluginLibs)
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)
    testImplementation(project(":shared-testing"))
    // IJ platform Logger.setFactory() references junit.rules.TestRule at class-init time.
    testRuntimeOnly(libs.junit4)
}
