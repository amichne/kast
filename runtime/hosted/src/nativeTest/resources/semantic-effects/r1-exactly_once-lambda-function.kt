package consumer

fun exactlyOnceLambdaFunction() {
    effects.exactlyOnce(block = { proof.target() }, marker = 1)
}
