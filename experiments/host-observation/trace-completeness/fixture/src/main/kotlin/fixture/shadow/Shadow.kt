package fixture.shadow
class StoredCallback { var callback: (() -> Unit)? = null }
fun also(store: StoredCallback, block: () -> Unit) { store.callback = block }
fun also(run: Boolean, block: () -> Unit) { if (run) block() }
fun negativeSink() = Unit
fun storedControl(store: StoredCallback) { also(store) { negativeSink() } }
fun conditionalControl(run: Boolean) { also(run) { negativeSink() } }
