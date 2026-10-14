package consumer

fun unknownLambdaFunction() {
    effects.unknown(block = { proof.target() }, marker = 1)
}
