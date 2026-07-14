package io.github.amichne.kast.api.continuation

fun interface ContinuationTokenIssuer<out Token> {
    fun issue(): Token
}
