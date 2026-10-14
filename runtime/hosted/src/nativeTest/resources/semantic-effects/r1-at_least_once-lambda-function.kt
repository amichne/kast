package consumer

fun atLeastOnceLambdaFunction() {
    effects.atLeastOnce(block = { proof.target() }, marker = 1)
}
