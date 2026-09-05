package kast.example.binding

/** Tests the proof policy only. This is deliberately not a fake claim of K2 acceptance. */
private class TestSymbol(val declaration: Int, val module: Int, val role: Int) {
    override fun toString(): String = error("presentation must not participate in binding")
}

private val testCompiler = CompilerAuthority<TestSymbol> { entry, target ->
    val registered = when (entry.slot.value) {
        0 -> TestSymbol(1, 1, 1)
        1 -> TestSymbol(2, 1, 1)
        else -> null
    }
    if (registered == null) CompilerComparison.Rejected(Difference.SOURCE_UNAVAILABLE)
    else
    when {
        target.module != registered.module -> CompilerComparison.Rejected(Difference.DIFFERENT_MODULE)
        target.role != registered.role -> CompilerComparison.Rejected(Difference.DIFFERENT_ROLE)
        target.declaration != registered.declaration -> CompilerComparison.Rejected(Difference.DIFFERENT_DECLARATION)
        else -> CompilerComparison.SameDeclaration
    }
}

fun referenceChecks(): List<String> {
    val passed = mutableListOf<String>()
    fun test(name: String, body: () -> Unit) { body(); passed += name }
    val epoch = Epoch.parse("a".repeat(64))
    val entry = RegistryEntry(epoch, DeclarationId.parse("existing-registry-identity"), RegistrySlot.fromOrdinal(0))
    val registered = TestSymbol(1, 1, 1)
    fun bind(target: TestSymbol, current: Epoch = epoch): BindingResult =
        ProvenBinding.bind(entry, current, target, testCompiler)
    test("same-declaration-reuses-exact-registry-entry") {
        val result = bind(TestSymbol(1, 1, 1)) as BindingResult.Complete
        check(result.binding.entry === entry)
    }
    test("same-name-or-location-does-not-admit-different-declaration") {
        check(bind(TestSymbol(2, 1, 1)) == BindingResult.Rejected(Difference.DIFFERENT_DECLARATION))
    }
    test("explicit-override-remains-distinct") {
        check(bind(TestSymbol(3, 1, 1)) is BindingResult.Rejected)
    }
    test("same-symbol-shape-in-different-module-is-rejected") {
        check(bind(TestSymbol(1, 2, 1)) == BindingResult.Rejected(Difference.DIFFERENT_MODULE))
    }
    test("class-and-constructor-sharing-anchor-are-distinct") {
        check(bind(TestSymbol(1, 1, 2)) == BindingResult.Rejected(Difference.DIFFERENT_ROLE))
    }
    test("stale-epoch-does-not-consult-compiler") {
        val result = ProvenBinding.bind(entry, Epoch.parse("b".repeat(64)), registered,
            CompilerAuthority<TestSymbol> { _, _ -> error("stale compiler access") })
        check(result == BindingResult.Rejected(Difference.STALE_EPOCH))
    }
    test("unavailable-proof-is-not-success") {
        val result = ProvenBinding.bind(entry, epoch, registered,
            CompilerAuthority<TestSymbol> { _, _ -> CompilerComparison.Rejected(Difference.SOURCE_UNAVAILABLE) })
        check(result is BindingResult.Rejected)
    }
    test("multiple-declarations-are-not-first-match") {
        val result = ProvenBinding.bind(entry, epoch, registered,
            CompilerAuthority<TestSymbol> { _, _ -> CompilerComparison.Rejected(Difference.MULTIPLE_DECLARATIONS) })
        check(result == BindingResult.Rejected(Difference.MULTIPLE_DECLARATIONS))
    }
    test("registry-entry-controls-independent-lookup") {
        val wrongEntry = RegistryEntry(epoch, entry.identity, RegistrySlot.fromOrdinal(1))
        val result = ProvenBinding.bind(wrongEntry, epoch, registered, testCompiler)
        check(result == BindingResult.Rejected(Difference.DIFFERENT_DECLARATION))
    }
    test("presentation-is-never-read") { check(bind(TestSymbol(1, 1, 1)) is BindingResult.Complete) }
    test("bound-entry-is-deterministic") {
        val first = (bind(TestSymbol(1, 1, 1)) as BindingResult.Complete).binding.entry
        val second = (bind(TestSymbol(1, 1, 1)) as BindingResult.Complete).binding.entry
        check(first == second)
    }
    test("malformed-epoch-is-rejected-at-ingress") {
        check(runCatching { Epoch.parse("not-a-generation") }.isFailure)
    }
    return passed
}
