package io.github.amichne.kast.indexstore

private val sourceIdentifierRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
private val packageRegex = Regex("""^\s*package\s+([\w]+(?:\.[\w]+)*)""", RegexOption.MULTILINE)
private val importRegex = Regex("""^\s*import\s+([\w]+(?:\.[\w]+)*)(\.\*)?""", RegexOption.MULTILINE)

/**
 * Parses the lightweight per-file data persisted in the source identifier index.
 */
fun parseSourceFileIndex(
    path: String,
    content: String,
    moduleName: String? = null,
): FileIndexUpdate {
    val (modulePath, sourceSet) = splitModuleName(moduleName)
    val imports = linkedSetOf<String>()
    val wildcardImports = linkedSetOf<String>()
    importRegex.findAll(content).forEach { match ->
        val fqName = match.groupValues[1]
        if (match.groupValues[2] == ".*") {
            wildcardImports += fqName
        } else {
            imports += fqName
        }
    }

    return FileIndexUpdate(
        path = path,
        identifiers = sourceIdentifierRegex.findAll(content).mapTo(linkedSetOf()) { match -> match.value },
        packageName = packageRegex.find(content)?.groupValues?.getOrNull(1),
        modulePath = modulePath,
        sourceSet = sourceSet,
        imports = imports,
        wildcardImports = wildcardImports,
    )
}
