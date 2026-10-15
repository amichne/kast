# Local Vale rules

The root `.vale.ini` uses these checked-in rules with Vale `3.24.0` from `mise.toml`.
A fresh checkout needs only the executable. Lint does not download styles or send prose to a service.

```shell
mise install vale
mise exec -- vale --no-global --config=.vale.ini --output=JSON README.md
```

The [documentation policy](../../docs/documentation-structure.md#local-vale-integration) defines the assessment procedure and glossary ownership.

## Source and licenses

These rules come from Repository Knowledge `3.1.0`, under `skills/controlled-technical-writing/assets/vale/`.
The import preserves rule bytes, licenses, and notices. The source manifest remains in `upstream-resources.lock.json`.

The manifest describes the complete upstream bundle. This directory imports its `styles/` and `licenses/` files.
The root `.vale.ini`, Kast vocabulary, and glossary rules belong to this repository.

| Rules | Source revision or release | Local license |
| --- | --- | --- |
| Slopsentral | Repository Knowledge `3.1.0` | [Kast and Slopsentral MIT license](../../LICENSE) |
| Commits and Conventional | [Commits v0.2.0](https://github.com/jdkato/commits/releases/tag/v0.2.0) | [License](licenses/Commits/LICENSE) and [notice](licenses/Commits/NOTICE) |
| Tutorial, HowTo, Reference, Explanation | [Diataxis v0.1.0](https://github.com/jdkato/diataxis/releases/tag/v0.1.0) | [License](licenses/Diataxis/LICENSE) and [notice](licenses/Diataxis/NOTICE) |
| Readability | [20b367bc](https://github.com/vale-cli/readability/tree/20b367bc5febf7ec9b83e176b8e611d230855838) | [License](licenses/readability/LICENSE) |
| Std | [5ce764fb](https://github.com/vale-cli/Std/tree/5ce764fb447663c3eca000978c4fbab2aeb2b41c) | [License](licenses/Std/LICENSE) and [notice](licenses/Std/NOTICE) |
| Google excessive claims | [9e2483e2](https://github.com/vale-cli/Google/tree/9e2483e2f09e7f623bacbbc154adb9627bed563b) | [License](licenses/Google/LICENSE) |

The import uses selected Google rules. It does not select the complete Google style.

## Local ownership

`styles/config/vocabularies/Kast/accept.txt` records reviewed spellings. A spelling exception does not define a domain concept.

`docs/glossary.json` owns canonical terms and rejected forms. Its exporter generates `styles/Kast/Terminology.yml`.
Edit the glossary first, then export and compare the rules through the documentation policy.

The root configuration selects terminology only for current authored documentation and agent instructions.
Historical reports keep their original terms. Code spans, fenced examples, and frontmatter use Vale's markup exclusions.

The root configuration also selects Diátaxis rules by reader purpose and Conventional Commits through the Commit view.
No style package, CMS account, MCP connection, or global configuration is necessary.

## Evidence limits

The shared assessment reports readability, sentence length, diction, claims, and glossary findings.
Root Vale also examines spelling, modal verbs, contractions, and page structure.
Neither procedure proves factual accuracy, full controlled-English conformance, or reader comprehension.

Keep unresolved findings with their rule, location, and reason. Do not change a source term solely to improve a score.
