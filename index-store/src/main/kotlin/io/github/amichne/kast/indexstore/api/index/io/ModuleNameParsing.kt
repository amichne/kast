package io.github.amichne.kast.indexstore.api.index

fun splitModuleName(moduleName: String?): Pair<String?, String?> {
    if (moduleName == null) return null to null
    val bracketIndex = moduleName.indexOf('[')
    if (bracketIndex < 0) return moduleName to null
    val closingIndex = moduleName.indexOf(']', bracketIndex + 1)
    if (closingIndex < 0) return moduleName to null
    return moduleName.substring(0, bracketIndex) to moduleName.substring(bracketIndex + 1, closingIndex)
}
