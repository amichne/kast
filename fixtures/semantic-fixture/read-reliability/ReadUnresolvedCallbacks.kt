package fixture.calls

// Intentionally unresolvable receiving call. The inner target is independently resolvable.
fun unresolvedCallback(): String = missingCallbackReceiver { inlineTarget() }
