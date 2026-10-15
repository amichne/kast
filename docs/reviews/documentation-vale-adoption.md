# Documentation value and terminology assessment

The README now presents Kast's value, three example questions, and one installation command.
The glossary defines 23 canonical terms. Root `AGENTS.md` applies those terms to current documentation and agent instructions.

This review uses source revision `52b5599de559d9b82f990bbd16b882762dbdf402` and the local documentation changes.
It covers the README, root instructions, current contributor guides, and all authored public MDX pages.
Generated contracts, OpenWiki pages, and historical reports remain outside the prose corpus.

## Findings and changes

| Finding | Evidence | Change |
| --- | --- | --- |
| The README mixed product value with runtime, lifecycle, and recovery detail. | The original page contained 206 lines and two internal flow diagrams. | The new page contains 62 lines and one installation command. Reference links supply detail. |
| Setup guides repeated a Codex connection after installation. | `install.sh` offers Codex MCP registration by default and calls the connection owner. | The README and setup guides use that default. Other integrations remain linked options. |
| Vale needed ignored styles and a manual vocabulary copy. | The original `StylesPath` named `.vale-local/styles`. | Rules, vocabulary, licenses, and provenance now reside in `config/vale/`. `mise.toml` pins Vale `3.24.0`. |
| The glossary excluded the README and agent instructions. | The original JSON scope covered public pages and two policy documents. | The glossary now covers current prose and instructions, with historical and literal exclusions. |
| The contributor guide described two site tabs. | `docs/public/docs.json` declares four reader tracks. | The guide now names Tutorials, How-to guides, Reference, and Explanation. |

The existing glossary supplies the same semantic distinctions.
New entries define declaration, diagnostic, Control, and Host from current source.
Code identifiers and runtime contracts keep their exact spelling.

## Prose measurements

The shared Repository Knowledge `3.1.0` procedure assessed the same 56 documents before and after the changes.
Both runs used Vale `3.24.0` and the same fixed prose policy.
The glossary digest and terminology scope changed during adoption.

| Advisory | Before | After |
| --- | --- | --- |
| Estimated grade greater than 8 | 47 | 46 |
| Diction | 57 | 54 |
| Sentences with more than 25 words | 26 | 25 |
| Claims | 4 | 4 |
| Total | 134 | 129 |

The estimated Flesch-Kincaid grade for the README changed from 11.17 to 7.30.
Its final shared assessment reports no findings. Root Vale also reports no README findings.
These results do not prove reader comprehension.

The [findings record](documentation-vale-adoption-findings.tsv) lists unresolved findings in changed prose, with exact rules, locations, and reasons.
The root engineering rules and contributor guide account for their inherited diction, modal, sentence-length, and claims findings.
Technical definitions and compatibility requirements keep their necessary terms despite grade advisories.
No rule or threshold changed to hide an advisory.

## Acceptance evidence

- Passed: nine terminology controls with real Vale and the repository configuration.
- Passed: the rejected form produces an error after adoption. The earlier configuration accepts the same input.
- Passed: canonical terms, code literals, and historical prose preserve their separate treatment.
- Passed: all 60 imported rule, license, vocabulary, and view digests match the source manifest.
- Passed: glossary registration, reader-view freshness, source paths, and generated terminology-rule bytes.
- Passed: 55 installer cases, JSON contracts, knowledge guards, and the generated callable reference.
- Passed: Mintlify site validation and the broken-link scan.
- Assessed: shared prose reports keep the unresolved advisories. Ordinary Vale exits zero for changed prose after spelling review.
- Not run: a native installation, an IDEA restart, and an agent connection from this checkout.
- Not assessed: comprehension by an independent human reader.

Raw reports remain under `build/reports/documentation/`.
`before.json` and `after.json` preserve corpus measurements, source digests, and located findings.
`changed-prose.json`, `changed-vale.json`, and `terminology-controls.json` preserve the focused evidence.
`gradle-guards.log` records the exact Gradle tasks and outcomes.

The repository guards used this command:

```shell
mise exec -- ./gradlew installerEntrypointTest verifyJsonContracts knowledgeImpact verifyKnowledgeBase :cli:verifyMintlifyCallableReference
```

The site checks used `npx --yes mint@4.2.841 validate` and `npx --yes mint@4.2.841 broken-links` from `docs/public`.

An author-only reader simulation found the problem, prerequisites, command, next question, and reference links directly in the README.
That simulation used no independent reader and does not establish human comprehension.

## Next documentation work

Review long procedural sentences in the contributor guide before further prose cleanup.
Keep the original requirements and their technical identifiers intact.
Review the source-map and recovery guides with readers who need those references.
Their high grade estimates alone do not establish a defect.

Keep the default path in the README stable when installation behavior changes.
Update the glossary and its generated rules together when a source concept changes.
