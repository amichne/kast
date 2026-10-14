package consumer

fun unknownLambdaConstructor() {
    effects.unknown(block = { proof.Target() }, marker = 1)
}
