plugins {
    id("kast.kotlin-library")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellijIdeaVersion = catalog.findVersion("intellij-idea").get().requiredVersion

// Re-use the IntelliJ distribution already extracted by :backend-standalone.
// This is a Gradle-user-home cache shared across builds.
private val intellijDistRoot = gradle.gradleUserHomeDir.resolve("kast/intellij-distributions/$intellijIdeaVersion")

dependencies {
    api(project(":analysis-api"))
    api(project(":index-store"))
    // K2 Analysis API standalone bootstrap classes (compileOnly — host provides at runtime).
    compileOnly(libs.analysis.api.standalone) {
        isTransitive = false
    }
    // IntelliJ platform JARs (PsiElement, TextRange, etc.) and Kotlin plugin JARs
    // (KtFile, KaSession, analyze, etc.) — compileOnly because each host (standalone
    // or IntelliJ plugin) provides its own copy at runtime.
    compileOnly(
        fileTree(intellijDistRoot.resolve("lib")) {
            include("*.jar")
            exclude("testFramework*.jar")
        },
    )
    compileOnly(
        fileTree(intellijDistRoot.resolve("plugins/Kotlin/lib")) {
            include("*.jar")
            exclude("jps/**")
        },
    )
    compileOnly(
        fileTree(intellijDistRoot.resolve("plugins/java/lib")) {
            include("*.jar")
        },
    )
}

// Ensure IntelliJ distribution is extracted before we compile.
tasks.named("compileKotlin") {
    dependsOn(":backend-standalone:extractIdeaDistribution")
}
