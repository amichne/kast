# Published packaging verification policy

This directory owns public-installer presentation checks, published hosted-delivery checks, and
the default isolated-runtime retirement verifier. These checks execute only staged or published
artifacts through public boundaries and must not recover runtime acquisition or process authority.

Keep Python support standard-library-only. Run the relocated public-installer shell check and
`./gradlew verifyNoDefaultIsolatedRuntimeNegative verifyNoDefaultIsolatedRuntime` after changes.
