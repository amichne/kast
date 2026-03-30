// analysis-common provides shared PSI and K2 Analysis API utilities used by both
// backend-intellij and backend-standalone. PSI and K2 Analysis API types are
// provided at runtime by each backend; this module compiles against them as compileOnly.
plugins {
    id("kas.kotlin-library")
}

// IJ distribution is populated by building backend-intellij first.
// If absent, run: ./gradlew :backend-intellij:build
private val ideaHomeOrNull: File? = fileTree(gradle.gradleUserHomeDir.resolve("caches/9.0.0/transforms")) {
    include("**/transformed/idea-2025.3-*/plugins/Kotlin/lib/kotlin-plugin.jar")
}.files.firstOrNull()?.parentFile?.parentFile?.parentFile?.parentFile

dependencies {
    api(project(":analysis-api"))

    if (ideaHomeOrNull != null) {
        val ideaHome = ideaHomeOrNull
        // Full IJ lib directory: provides PsiElement, PsiFile, TextRange, etc.
        compileOnly(fileTree(ideaHome.resolve("lib")) {
            include("**/*.jar")
            exclude("testFramework.jar")
            exclude("testFramework-k1.jar")
        })
        // Kotlin plugin: provides KtFile, KtNamedDeclaration, K2 Analysis API types, etc.
        compileOnly(fileTree(ideaHome.resolve("plugins/Kotlin/lib")) {
            include("**/*.jar")
            exclude("jps/**")
        })
        // Java plugin: provides PsiMethod, PsiField, PsiClass (for Java-aware symbol mapping).
        compileOnly(fileTree(ideaHome.resolve("plugins/java/lib")) { include("**/*.jar") })
    }
}
