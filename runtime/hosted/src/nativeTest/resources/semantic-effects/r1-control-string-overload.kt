package consumer

fun control() {
    effects.exactlyOnce(block = { proof.target() }, marker = "control")
}
