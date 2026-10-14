package consumer

fun atLeastOnceLambdaConstructor() {
    effects.atLeastOnce(block = { proof.Target() }, marker = 1)
}
