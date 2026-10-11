# Exact descriptions without serialized dictionary keys

Base: freshly fetched and pulled main `059d7cdc57d45a5404c3a31d8d6dcaa7e3f00dde`, the protected squash merge of #1003. Its tree `06bb42975a43357100483e6c72dc441db87b1dde` equals reviewed candidate `ec2db6cf29d33dfa600eeae569c193a0200db5cd`. Open PR/issue inspection found no competing presentation lane; #987 remains the query-delivery track and rollout owns native qualification/retention accounting.

## Contract and decision

Keep compact text byte-identical, canonical/verbose results unchanged, every distinct occurrence and failure intact, and existing detail authority rules. Keep all semantic, transport, canonical-envelope and presentation quotas unchanged. Change only how the presentation dictionary finds equal declaration descriptions, and regenerate the existing Pi/Copilot owner blocks.

Previously every target/owner declaration was fully JSON-serialized to form a dictionary key. Now the exact selector chooses a candidate bucket, then all admitted JSON fields are compared recursively, including field order, array/object shape and nested compiler proof. Selector identity alone never establishes equal descriptions. The first matching full value owns the description and insertion order; a WeakMap retains its entry for row substitution. Missing selectors share a candidate bucket and still require complete equality. This avoids key strings; it does not remove structural comparison work or establish a CPU/latency speedup. Conflicting descriptions in one bucket can require multiple comparisons; the existing bounded result envelope remains the resource bound.

Rejected alternatives: selector-only grouping can erase distinct proof/location/fields; object identity alone misses equal values in separately decoded replies; hashes still serialize values and need collision proof. No shared comparison framework or native change is introduced.

## Fixed pure measurements

| UTF-8 string-byte boundary | Merged #1003 | Follow-up |
| --- | ---: | ---: |
| Dense 20-occurrence presentation serialization bytes / calls | 53,320 / 68 | 27,191 / 28 |
| Full declaration dictionary-key bytes / calls | 26,129 / 40 | 0 / 0 |
| Dense visible text bytes | 26,228 | 26,228 |
| Complete 43-reference presentation serialization bytes / calls | 6,124 / 50 | 6,124 / 50 |
| Complete visible text bytes | 5,073 | 5,073 |

The dense reduction is 26,129 generated JSON-string bytes (49.0%) versus merged #1003. The original pre-#1003 canonical-only presentation serialized 40,084 bytes: the follow-up is 32.2% below that boundary. Dense visible output SHA-256 stays `faa2cb563ad42d980c22ab93a401b3565a0a64839528ee4a6b311c83a26447a2`. The instrumented production owner counts every JSON.stringify return, including dictionary keys, separately from wire reads, SDK archives and provider input. These are string-work counts, not CPU, native-work, wall-time, token or spend claims.

Existing pinned canonical documents and exact expansion tests remain the reference/evidence oracle. New counterexamples use the same selector with different names, ranges, nested compiler proof, files and different field order; none coalesce. Occurrence row IDs remain distinct. The named counter regression failed at 53,320/68 before the implementation and passes at 27,191/28 afterward.

## Canonical drainage boundary and options

A separate deterministic admitted-provider fixture reuses pinned declaration shapes, creates 185 distinct occurrence row IDs and supplies 20 rows per page. It is synthetic, not a native 185-row or retention-capacity receipt. Exact oracle/cursors establish one RUN followed by output reads. The unchanged production drain reads seven RPC replies (279,333 JSON bytes), retains six included pages/120 rows in a 239,645-byte canonical envelope, and excludes the received seventh page that cannot fit with the existing 4,096 reserve. Compact visible text is 92,614 bytes; stop remains BYTE_LIMIT with the original total 185 and retained detail request. The received-but-excluded bytes stay accounted. No evidence is inferred from an unread suffix and no semantic recovery occurs.

This change cannot buy drainage: the existing 262,144-byte canonical envelope decides inclusion before compact presentation, with an independent 64-RPC bound. The regression freezes that boundary and all exact received rows. Do not attribute it to native ownership or model incapability; rollout's matched current ledger is needed for native claims.

Options for a later independently reviewed contract: measure/cache exact reply-length accounting to avoid repeated canonical serializations while retaining the same inclusion rule; or define a separately bounded compact delivery artifact with original terminal authority and explicit detail handles, keeping verbose canonical behavior and every failure/evidence obligation provable. The latter changes the outer delivery contract and resource ownership and is intentionally deferred. Raising caps, dropping failure evidence or silently rerunning semantics is excluded.

## Compatibility and validation

Native Host, protocol schemas, CLI transport/session, installation and output-drain decisions are unchanged. This slice can update generated adapters without a Host replacement. #1003's invocation-owned session still requires its compatible local Control/CLI; older catalogs keep one-shot fallback. Machine JSON consumers continue to use verbose:true. Source/candidate provenance must retain its original commit identity even when its tree equals a squash merge.

Pure validation: 42 Node presentation/session tests passed with optional installed Pi 1.0.2/OpenAI Codex provider conversion enabled; 15 generator tests and generated parity passed. Provider content is unchanged; the seven installed conversion cases still exclude canonical SDK details from input while preserving them locally. No credentials, session, inference, IDEA, installation or native retention work runs in this phase. The standalone adapter suite requires its typed Kotlin-produced fixtures and is validated through the owning JVM bridge/product gate, not a reconstructed catalog. Mandatory publication gate and final-head remote CI are tracked on the draft PR.
