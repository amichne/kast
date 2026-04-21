╰─➤  kast demo
╭─ Act 0 of 3 — Setup ─────────────────────────────╮
│kast demo                                         │
│semantic analysis vs grep — three acts, one symbol│
│                                                  │
│Workspace  /Users/amichne/code/kast               │
╰──────────────────────────────────────────────────╯› Warming workspace daemon (kast workspace ensure)...✓ workspace ensure (84ms)› Discovering workspace symbols (kast workspace-symbol)...✓ workspace symbol search (4.82s)› Auto-selecting a noisy symbol (--min-refs=5, --noise-ratio=2.0)...╭─ demo target ──────────────────────────────────────────────────────────────╮
│Symbol  io.github.amichne.kast.cli.CliCommand.Help                          │
│Kind    class                                                               │
│Vis     public                                                              │
│File    kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommand.kt:22│
│                                                                            │
│Find semantic references — preview a safe rename — trace incoming callers.  │
╰────────────────────────────────────────────────────────────────────────────╯› Classifying grep matches for Help...───────────────────────────────────── Act 1 of 3 — Text Search ─────────────────────────────────────
grep -rn "Help" --include="*.kt"
╭──────────────────────┬───────┬─────────────────╮
│ Category             │ Count │ Bucket          │
├──────────────────────┼───────┼─────────────────┤
│ Likely correct       │ 16    │ real call sites │
├──────────────────────┼───────┼─────────────────┤
│ Imports              │ 7     │ ambiguous       │
├──────────────────────┼───────┼─────────────────┤
│ Comments             │ 6     │ noise           │
├──────────────────────┼───────┼─────────────────┤
│ String literals      │ 26    │ noise           │
├──────────────────────┼───────┼─────────────────┤
│ Substring collisions │ 32    │ noise           │
╰──────────────────────┴───────┴─────────────────╯
╭──────────────────────────────────────────────────────────┬──────┬────────────────────────────────╮
│ File:Line                                                │ Categ│ Preview                        │
├──────────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ parity-tests/src/test/kotlin/io/github/amichne/kast/parit│ comme│ // --- Helpers ---             │
├──────────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ backend-standalone/src/test/kotlin/io/github/amichne/kast│ strin│ val declarationFile = workspace│
├──────────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ backend-standalone/src/test/kotlin/io/github/amichne/kast│ strin│ assertEquals("sample.LegacyHelp│
├──────────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ backend-standalone/src/test/kotlin/io/github/amichne/kast│ strin│ relativePath = "app/src/main/ja│
├──────────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ backend-standalone/src/test/kotlin/io/github/amichne/kast│ subst│ public final class LegacyHelper│
├──────────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ backend-standalone/src/test/kotlin/io/github/amichne/kast│ subst│ private LegacyHelper() {}      │
╰──────────────────────────────────────────────────────────┴──────┴────────────────────────────────╯
87 grep hits. No type information. No scope. Just noise.
sed across 20 files would touch 64 non-symbol matches.────────────────────────────────── Act 2 of 3 — Symbol Resolution ──────────────────────────────────
kast resolve ⇒ io.github.amichne.kast.cli.CliCommand.Help
Declared in: kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommand.kt:22
Visibility:  public
Kind:        class
╭───────────────────────────────────────────────┬────┬───────┬─────────────────────────────────────╮
│ File                                          │ Lin│ Module│ Preview                             │
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/main/kotlin/io/github/amichne/kas│ 37 │ [kast-│ return CliCommand.Help()            │
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/main/kotlin/io/github/amichne/kas│ 40 │ [kast-│ return CliCommand.Help(parsed.positi│
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/main/kotlin/io/github/amichne/kas│ 43 │ [kast-│ return CliCommand.Help(parsed.positi│
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/main/kotlin/io/github/amichne/kas│ 72 │ [kast-│ return CliCommand.Help(parsed.positi│
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/main/kotlin/io/github/amichne/kas│ 146│ [kast-│ return CliCommand.Help(listOf("skill│
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/main/kotlin/io/github/amichne/kas│ 44 │ [kast-│ is CliCommand.Help -> CliExecutionRe│
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/test/kotlin/io/github/amichne/kas│ 25 │ [kast-│ assertEquals(CliCommand.Help(), comm│
├───────────────────────────────────────────────┼────┼───────┼─────────────────────────────────────┤
│ kast-cli/src/test/kotlin/io/github/amichne/kas│ 32 │ [kast-│ assertEquals(CliCommand.Help(listOf(│
├───────────────────────────────────────────────┴────┴───────┴─────────────────────────────────────┤
│ … and 3 more references                                                                          │
╰──────────────────────────────────────────────────────────────────────────────────────────────────╯
╭─ rename --dry-run  (Help → HelpRenamed) ─────────────────────────────────────────────╮
│12 edits across 5 files — 5 pre-image hashes captured.                                │
│  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommand.kt                   │
│  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt             │
│  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliExecution.kt                 │
│  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt         │
│  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillCommandParsingTest.kt│
╰──────────────────────────────────────────────────────────────────────────────────────╯
incoming callers: 201
max depth:        2
files visited:    5╭─ Act 3 · walk the symbol graph ─────────────────────────────────────────────────────────╮
│interactive walker                                                                       │
│                                                                                         │
│Hop between references, callers, and callees — every move is anchored to symbol identity.│
│                                                                                         │
│r <n>  jump to reference #n        c <n>  jump to incoming caller #n                     │
│o <n>  jump to outgoing callee #n  g [n]  compare against grep (n lines)                 │
│s      show current declaration    b      pop the last hop                               │
│h      help                         q      finish the walker                             │
╰─────────────────────────────────────────────────────────────────────────────────────────╯╭─ current node · io.github.amichne.kast.cli.CliCommand.Help ──────────────────────────────────────╮
│name     Help                                                                                     │
│kind     class                                                                                    │
│vis      public                                                                                   │
│file     kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommand.kt:22                     │
│                                                                                                  │
│references (11)                                                                                   │
│  ├── [r 1]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:37  return Cl│
│  ├── [r 2]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:40  return Cl│
│  ├── [r 3]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:43  return Cl│
│  ├── [r 4]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:72  return Cl│
│  ├── [r 5]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:146  return C│
│  ├── [r 6]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliExecution.kt:44  is CliCommand│
│  ├── [r 7]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:25  asser│
│  ├── [r 8]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:32  asser│
│  └── ... and 3 more                                                                              │
│                                                                                                  │
│incoming callers (11)                                                                             │
│  ├── [c 1]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 2]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 3]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 4]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 5]  parseSkillCommand · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/Cli│
│  ├── [c 6]  execute · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliExecution.│
│  ├── [c 7]  no arguments opens help · function  kast-cli/src/test/kotlin/io/github/amichne/kast/c│
│  ├── [c 8]  namespace arguments open contextual help · function  kast-cli/src/test/kotlin/io/gith│
│  └── ... and 3 more                                                                              │
│                                                                                                  │
│outgoing callees (1)                                                                              │
│  └── [o 1]  CliCommand · interface  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliComman│
╭─ current node · Help ────────────────────────────────────────────────────────────────────────────╮
│name     Help                                                                                     │
│kind     unknown                                                                                  │
│vis      public                                                                                   │
│file     kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommand.kt:22                     │
│                                                                                                  │
│references (9)                                                                                    │
│  ├── [r 1]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:37  return Cl│
│  ├── [r 2]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:40  return Cl│
│  ├── [r 3]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:43  return Cl│
│  ├── [r 4]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:72  return Cl│
│  ├── [r 5]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:146  return C│
│  ├── [r 6]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:25  asser│
│  ├── [r 7]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:32  asser│
│  ├── [r 8]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:39  asser│
│  └── ... and 1 more                                                                              │
│                                                                                                  │
│incoming callers (9)                                                                              │
│  ├── [c 1]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 2]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 3]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 4]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 5]  parseSkillCommand · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/Cli│
│  ├── [c 6]  no arguments opens help · function  kast-cli/src/test/kotlin/io/github/amichne/kast/c│
│  ├── [c 7]  namespace arguments open contextual help · function  kast-cli/src/test/kotlin/io/gith│
│  ├── [c 8]  completion namespace opens contextual help · function  kast-cli/src/test/kotlin/io/gi│
│  └── ... and 1 more                                                                              │
│                                                                                                  │
│outgoing callees (1)                                                                              │
│  └── [o 1]  CliCommand · interface  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliComman│
╭─ current node · Help ────────────────────────────────────────────────────────────────────────────╮
│name     Help                                                                                     │
│kind     unknown                                                                                  │
│vis      public                                                                                   │
│file     kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommand.kt:22                     │
│                                                                                                  │
│references (9)                                                                                    │
│  ├── [r 1]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:37  return Cl│
│  ├── [r 2]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:40  return Cl│
│  ├── [r 3]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:43  return Cl│
│  ├── [r 4]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:72  return Cl│
│  ├── [r 5]  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParser.kt:146  return C│
│  ├── [r 6]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:25  asser│
│  ├── [r 7]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:32  asser│
│  ├── [r 8]  kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliCommandParserTest.kt:39  asser│
│  └── ... and 1 more                                                                              │
│                                                                                                  │
│incoming callers (9)                                                                              │
│  ├── [c 1]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 2]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 3]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 4]  parse · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandParse│
│  ├── [c 5]  parseSkillCommand · function  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/Cli│
│  ├── [c 6]  no arguments opens help · function  kast-cli/src/test/kotlin/io/github/amichne/kast/c│
│  ├── [c 7]  namespace arguments open contextual help · function  kast-cli/src/test/kotlin/io/gith│
│  ├── [c 8]  completion namespace opens contextual help · function  kast-cli/src/test/kotlin/io/gi│
│  └── ... and 1 more                                                                              │
│                                                                                                  │
│outgoing callees (1)                                                                              │
│  └── [o 1]  CliCommand · interface  kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliComman│
walker› ^C%                                                                                                                                                                                                                                                                                                  ╭─amichne@Austins-MacBook-Pro ~/code/kast  ‹main*›
╰─➤                                                                                                                                                                                                                                                                                                    130 ↵
