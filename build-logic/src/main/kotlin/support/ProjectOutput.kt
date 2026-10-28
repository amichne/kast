package support

import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.provider.Provider

/** Keeps an output's producer dependency without access to another project's mutable model. */
fun Project.consumeProjectOutput(
    name: String,
    producer: String,
    variant: String,
): Provider<ResolvableConfiguration> {
    val scope = configurations.dependencyScope("${name}Dependencies") {
        dependencies.add(this@consumeProjectOutput.dependencies.project(mapOf("path" to producer, "configuration" to variant)))
    }
    return configurations.resolvable(name) {
        isTransitive = false
        extendsFrom(scope.get())
    }
}
