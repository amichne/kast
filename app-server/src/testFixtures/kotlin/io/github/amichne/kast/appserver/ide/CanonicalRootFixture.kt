package io.github.amichne.kast.appserver.ide

import java.nio.file.Path

/** Case-owned filesystem observation for pure client admission tests. */
fun canonicalRootFixture(path: Path): CanonicalRoot = CanonicalRoot(path)
