package io.github.amichne.kast.workspace.intellij.read

/** Explicit invocation identity survives native callbacks on a different thread. */
enum class IntellijReadSearch(val call: IntellijReadCall) {
    REFERENCES(IntellijReadCall.REFERENCE_SEARCH),
    DEFINITIONS(IntellijReadCall.DEFINITION_SEARCH),
    SCOPED_FILES(IntellijReadCall.SCOPED_FILE_INDEX),
    EXACT_PACKAGE_FILES(IntellijReadCall.EXACT_PACKAGE_INDEX),
}

interface IntellijReadSearchScope : IntellijReadCallScope {
    /** Called before cancellation or classification, including excluded callbacks. */
    fun callbackEntered()
}

inline fun <Value> IntellijReadObservation.search(
    search: IntellijReadSearch,
    crossinline effect: (IntellijReadSearchScope) -> Value,
): Value {
    val scope = enterSearch(search)
    return scope.observe { effect(scope) }
}
