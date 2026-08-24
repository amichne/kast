# Topology contract API proof

This directory owns the compiled topology-contract ABI projection and its exact checked-manifest
verification task.

- Project every non-synthetic, non-inlined public or protected class and every non-synthetic public
  or protected field and method from all main JVM class outputs.
- Preserve ABI-relevant access, generic signature, throws, permitted-subclass, and constant-value
  evidence in deterministic manifest entries.
- Reject empty policies, classfile sets, manifests, duplicate classes, and non-canonical policy
  names as closed typed states.
- Keep deferred graph/path/query names in the fixed zero-budget policy; operation-name checks are a
  separate registry boundary.

Run `./gradlew -p build-logic test --tests support.pr633.VerifyTopologyContractApiTaskTest`, then
run `./gradlew verifyTopologyContractApi`.
