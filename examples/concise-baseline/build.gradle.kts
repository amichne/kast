import kast.baseline.program.*
import kast.baseline.build.BaselineGate
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) apply false }

require(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) { "The example build requires Java 21 or newer." }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    val isVerificationModule = name == "verification"
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        if (isVerificationModule) sourceSets.named("main") { kotlin.srcDir(rootProject.file("program")) }
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    val module = ExampleModule.entries.single { it.path == path }
    module.dependencies.forEach { dependencies.add("implementation", project(it)) }
}

val verificationClasspath = project(":verification").extensions.getByType<SourceSetContainer>().named("main").map { it.runtimeClasspath }
val gates = BaselineProgram.graph().nodes.associate { node ->
    node.id to tasks.register<BaselineGate>(node.publicInterface) {
        group = "baseline proof"
        description = node.goal
        gateId.set(node.id.name)
        checkout.set(layout.projectDirectory.dir("../.."))
        executionClasspath.from(verificationClasspath)
        javaExecutable.set(File(System.getProperty("java.home"), "bin/java").absolutePath)
        proofDirectory.set(layout.buildDirectory.dir("proofs/${node.id.name}"))
        outputs.upToDateWhen { false }
        if (node.action !is GateAction.Unimplemented) dependsOn(":verification:classes")
    }
}
BaselineProgram.graph().nodes.forEach { node -> gates.getValue(node.id).configure {
    dependsOn(node.dependencies.map(gates::getValue))
    inputs.files(node.dependencies.map { predecessor -> layout.buildDirectory.file("proofs/${predecessor.name}/receipt.json") })
} }
tasks.register("verifyBaselinePrototype") {
    group = "verification"
    dependsOn(gates.getValue(GateId.TRUST), gates.getValue(GateId.BOUNDARIES))
}
tasks.register("verifyBaselineDelivery") {
    group = "verification"
    dependsOn(gates.getValue(GateId.REVALIDATION))
}
tasks.register<JavaExec>("projectBaselineProgram") {
    dependsOn(":verification:classes")
    classpath(verificationClasspath)
    mainClass.set("kast.baseline.verification.MainKt")
    args("projection")
}
