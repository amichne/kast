# Delivery-program tests

Use JUnit Jupiter through the existing build-logic test contract. Test observable program invariants: exact target identity, derived graph order and waves, terminal reachability, requirement-trace derivation, proof-gate ownership, and rejection of invalid graph structure.

Run:

```shell
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveReusedIndexProgramTest
```
