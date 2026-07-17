package io.github.amichne.kast.headless

fun interface HeadlessGradleImportObserver {
    fun observe(): HeadlessGradleImportObservation
}
