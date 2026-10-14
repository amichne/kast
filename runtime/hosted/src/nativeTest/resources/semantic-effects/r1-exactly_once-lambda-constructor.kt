package consumer

fun exactlyOnceLambdaConstructor() {
    effects.exactlyOnce(block = { proof.Target() }, marker = 1)
}
