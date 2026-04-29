plugins {
    id("kast.kotlin-library")
    id("kast.kotlin-serialization")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.toml)
    testImplementation(project(":shared-testing"))
}

tasks.register<JavaExec>("generateOpenApiSpec") {
    description = "Generates the OpenAPI 3.1 YAML specification for the analysis API"
    group = "documentation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.api.docs.OpenApiDocumentKt")
    val outputFile = rootProject.layout.projectDirectory.file("docs/openapi.yaml")
    args(outputFile.asFile.absolutePath)
}

tasks.register<JavaExec>("generateDocPages") {
    description = "Generates Markdown capability and API reference pages from the model registry"
    group = "documentation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.api.docs.DocsDocumentKt")
    val outputDir = rootProject.layout.projectDirectory.dir("docs/reference")
    args(outputDir.asFile.absolutePath)
}

tasks.register<Exec>("checkDocsBuild") {
    description = "Builds the Zensical/MkDocs site and validates that no Material icon shortcodes are left unrendered in HTML output"
    group = "documentation"
    dependsOn("generateDocPages")
    workingDir = rootProject.projectDir
    commandLine(
        "bash", "-c",
        """
        zensical build --clean 2>&1 || { echo "ERROR: zensical build failed"; exit 1; }
        python3 - <<'EOF'
import re, glob, sys
shortcode = re.compile(r':material-[a-z0-9-]+:(?:\{[^}]*\})?')
issues = []
for f in sorted(glob.glob('site/**/*.html', recursive=True)):
    content = open(f).read()
    stripped = re.sub(r'<(?:code|pre)[^>]*>.*?</(?:code|pre)>', '', content, flags=re.DOTALL)
    for m in shortcode.finditer(stripped):
        issues.append((f, m.group()))
if issues:
    print('ERROR: Unrendered Material icon shortcodes found in built HTML:')
    for path, match in issues[:10]:
        print(f'  {path}: {match}')
    sys.exit(1)
else:
    print(f'OK: No unrendered Material shortcodes ({len(list(glob.glob("site/**/*.html", recursive=True)))} pages checked)')
EOF
        """.trimIndent()
    )
}
