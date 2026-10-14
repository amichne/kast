package consumer

fun atMostOnceLambdaFunction() {
    effects.atMostOnce(block = { proof.target() }, marker = 1)
}
