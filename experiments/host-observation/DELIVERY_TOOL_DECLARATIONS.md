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

## Full-body measurement

The installed converter reproduced the sealed baseline SHA-256 exactly. The
after measurement exercised that same converter and its final JSON serialization;
it is not subtraction from a component estimate. An abort signal stops each
scripted observation after serialization and before fetch. All observations used
synthetic authentication syntax; no credentials were read. No provider, network,
adapter, native or semantic execution occurred.

| Full serialized request / preserved component | Before | After |
| --- | ---: | ---: |
| Saved final DELIVERY body | 329,306 B | 255,158 B |
| Top-level declarations including field wrapper | 74,148 B | 0 B |
| Original converted input | 252,365 B | 252,365 B |
| Instructions including partial-result limitations | 2,441 B | 2,441 B |
| Excess over unchanged 262,144 B ceiling | 67,162 B | 0 B |
| Headroom below ceiling | 0 B | 6,986 B |
| WORK control body | 328,784 B | 328,784 B |
| Issued evidence-read DELIVERY control body | 328,784 B | 328,784 B |

Fresh result-observation and saved restoration paths produced the same after
body hash. The controls deliberately reuse the large historical context to prove
byte equality; both remain over the ceiling. Added synthetic context yielded
265,218 B after removal and was rejected by 3,074 B. Tools are still forbidden
at the execution boundary, even if an assistant proposes one without a declaration.

The saved archive/context and canonical text hashes remain
`c1dcc190f61296d08fa44cc7a62c0d2a27b5702823858de233e09812c91eda32`,
`caa04d6b4bd715c8926b5bd51f2cf4d2a91c12463950fc3a6e6f8f4db36ba553`, and
`b667ac5a71e7e588ac763ebf32223570d62d39c96973e01993a950eea5afe801`.
Before/after body hashes are
`45ca41c6dcf2bb69e4ec3d8b5ba96018bb828e307882d9de9920f99ab0f4a0d0` and
`cc3f5eeec462070d15b4621038fa440e87bd9840ee29d1f87cd79e12f54eda1b`.
The Pi 1.0.2 provider converter hash is
`b185956e11a5c5cb522e5cad77daf21ce31c3c304e86a8333ffcb49ff78466ea`.
Local receipts, complete bodies and replay script stay in the task's owned
`query-delivery-evidence/delivery-tool-declarations/` directory outside Git.

This proves local request shape, complete-body admission and one permitted final
request. It does not prove a provider send, answer fidelity, backend compatibility,
token spend or refreshed native authority. The installed converter still builds
historical declarations before the guard removes them; conversion CPU and transient
memory are not reduced. Inline historical additions and other SDK/provider versions
are outside this measured case. Parent owns reviews, merge, installation and any
actual saved one-answer send.

## Validation

The focused regressions failed before the change. All 180 Node policy, payload,
worker and controller tests pass afterward, without failures or skips. The owning
mutation test rejects all 22 decision mutations, including declaration retention,
removal during WORK and removal during an issued evidence read. The existing
complete-body preflight rejects baseline and excess-context bodies and admits the
255,158-byte after body under the unchanged plan. The required product gate and
exact-head CI results are recorded in the PR and owned evidence receipts.
