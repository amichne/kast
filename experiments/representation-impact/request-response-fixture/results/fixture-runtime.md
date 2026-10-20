# Fixture runtime result

```text
Command: ./gradlew -p experiments/representation-impact/request-response-fixture assertFixture --console=plain
Exit code: 0
PASSED: frozen inputs, nonempty success, empty success, backend error, echo, constant mapper, matched calls, oracle outcomes
```

The first run failed because of a parenthesis error in the runtime harness.
That failure came from fixture compilation.

The negative control then exposed a missing rule in the oracle evaluator.
A field in a forbidden argument escaped the evaluator.
The same command passed after the evaluator correction.

This procedure does not establish native Kast behavior.
