package consumer

fun atMostOnceLambdaConstructor() {
    effects.atMostOnce(block = { proof.Target() }, marker = 1)
}
