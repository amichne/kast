# Reviewed evidence sharing oracle

The detached cases compare equal but physically distinct callable proofs for `Let` and `FirstOrNull`.
Different value paths keep the exact sites distinct.
The existing model fixtures supply assumed compiler facts.
These cases do not establish native resolution, artifact membership, body summaries, or runtime performance.

Four cases protect full proof, scope, model provenance, predicates, paths, qualifications, and native receipts.
Two separate cases specify coherent physical sharing and a standalone byte estimate that does not increase.
Failures in those two cases establish `PERFORMANCE_RED`, not a semantic defect.
The existing byte policy and the model fixture allowance remain unchanged.

`TestReporter` records process-local object IDs and policy bytes before and after the same sharing call.
The reference-identity assertions supply the identity proof.
The IDs aid diagnosis and carry no semantic authority.
The receipt assertions preserve the native read's original work, result count, byte receipt, and domain.

The nearest controls keep another exact scope and another artifact module distinct.
Neither control permits a binding to discard its current provenance.
The conditional collection selection keeps its field suffix.
The reviewed callback keeps its exact formal, argument, matched frame, and model provenance.

The focused procedure is:

```sh
mise exec -- ./gradlew --no-daemon --max-workers=1 :relation:contract:test --tests '*ValueReviewedEvidenceSharingTest' --console=plain
```
