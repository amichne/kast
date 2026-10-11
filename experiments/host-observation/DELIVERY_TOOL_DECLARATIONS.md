# Final answer tool declarations

This follow-up starts from main `f85a194e374a45fb79ea97d9ccfcd97282fffae2`.
The installed Pi 1.0.2 converter produced a 329,306-byte saved partial-answer
request. Its unchanged body ceiling is 262,144 bytes. Three tool declarations
occupy 74,139 bytes although the policy forbids every tool in this final answer
phase. The sealed no-inference qualification rejected that request before send.

## Contract

- `CasePolicy` owns whether DELIVERY is a final answer with no remaining issued
  evidence request. The existing final provider payload guard omits top-level
  tool declarations only in that state, before complete-body admission.
- WORK and DELIVERY with a permitted product-issued evidence read retain their
  existing declarations and execution policy. Final DELIVERY still rejects every
  tool proposal before adapter execution; declaration removal grants no authority.
- Preserve transcript, question, canonical result/evidence, instructions, output
  cap, usage accounting, request count, byte ceilings and owned timeout behavior.
  Saved and fresh result paths use the same policy and guard. Context that exceeds
  the ceiling after removal still fails honestly before send.
- Verify the full before/after bodies through the actual installed provider
  converter with no inference, credentials, network, native work or installation.
  Record source identities, complete serialized bytes and equality of retained
  content. Pure regressions run the production policy/guard first.

## Decision log

The converter derives top-level tools from transcript declarations. The SDK's
active-tool setter also rebuilds the system prompt. Keep those historical facts
intact and remove unavailable declarations at the existing final payload boundary.
This is a scoped harness delivery change; native query results, adapter contracts
and semantic acquisition remain unchanged.

Measured after-change evidence and required gate status will be added before the
draft is handed back for review. Local qualification evidence stays outside Git.
