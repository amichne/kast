package support.tasks.idea

import java.io.File
import org.gradle.api.GradleException

/** Selects one real vendor layout; ambiguous metadata cannot silently change the build identity. */
fun selectedIdeaProductInfo(home: File): File {
    val candidates = listOf(home.resolve("Resources/product-info.json"), home.resolve("product-info.json"))
        .filter(File::isFile)
    if (candidates.size != 1) throw GradleException("Selected IDEA must have exactly one vendor product-info.json")
    return candidates.single()
}
