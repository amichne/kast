package io.github.amichne.kast.headless

data class HeadlessGradleModelReadiness(
    val moduleNames: List<String>,
    val kotlinSourceModuleNames: List<String>,
    val compilerReadyKotlinModuleNames: List<String>,
) {
    init {
        require(moduleNames == moduleNames.distinct().sorted()) { "moduleNames must be sorted and unique" }
        require(kotlinSourceModuleNames == kotlinSourceModuleNames.distinct().sorted()) {
            "kotlinSourceModuleNames must be sorted and unique"
        }
        require(compilerReadyKotlinModuleNames == compilerReadyKotlinModuleNames.distinct().sorted()) {
            "compilerReadyKotlinModuleNames must be sorted and unique"
        }
        require(moduleNames.containsAll(kotlinSourceModuleNames)) { "Kotlin source modules must belong to the imported model" }
        require(kotlinSourceModuleNames.containsAll(compilerReadyKotlinModuleNames)) {
            "compiler-ready Kotlin modules must be Kotlin source modules"
        }
    }

    val unavailableKotlinModuleNames: List<String>
        get() = kotlinSourceModuleNames - compilerReadyKotlinModuleNames.toSet()

    val compilerReady: Boolean
        get() = kotlinSourceModuleNames.isNotEmpty() && unavailableKotlinModuleNames.isEmpty()
}
