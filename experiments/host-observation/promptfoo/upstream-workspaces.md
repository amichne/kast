# Compare delegates on upstream workspaces

Each case asks a maintenance question about a pinned upstream commit. The source and Kast arms receive the same question and tracked files.
Both arms can search with a regular expression and read numbered lines. The Kast arm also receives the installed skill body and read-only Kast commands.
The oracle stays outside the workspace. Promptfoo grades the exact set of answers after the participant exits.

| Workspace | Maintenance question | Plausible error |
|---|---|---|
| `ktfmt` | Which calls resolve to Java `OpsBuilder.sync(int)`? | Miss the implicit receiver or include the Kotlin extension. |
| `ktfmt` | Which prefix expressions call `Indentation.Const.unaryMinus`? | Include a call to the base override or primitive negation. |
| kotlinx.coroutines | Which production functions directly call `delay(Long)`? | Include an indirect ticker caller. |
| kotlinx.coroutines | Which platform aliases implement the production `TestResult`? | Include a test utility from another package. |
| Spring Framework | Which Java JDBC callback overload does each Kotlin call select? | Confuse the extractor and row handler. |
| Spring Framework | Which WebFlux functions call the Java primitive status overload? | Include an MVC function or the `HttpStatusCode` overload. |
| Spring DSL holdout | Which route expressions invoke a Kotlin operator that calls Java `RequestPredicates.path(String)`? | Select the enclosing test class instead of the active DSL receiver. |

Each workspace catalog records its commit, source locations, answer sets, and wrong answers. The Spring DSL holdout uses the same commit. The full checkouts stay outside Kast.
The `ktfmt` call sites also have a bytecode oracle. Run `:ktfmt:compileKotlin` and examine the invoked owner and JVM descriptor with `javap -p -c -l`.
Map each matching instruction back through its line table.

For Spring 7.0.7, the JVM descriptors distinguish the JDBC callback interfaces, the primitive status overload, and the MVC owner.
Kotlin 2.2.21 compiled the pinned JDBC and router tests. The disassembly links test lines to Kotlin wrappers and then to Java methods.

For `kotlinx-coroutines-core-jvm:1.11.0`, `javap -c -p` found 13 calls to the JVM descriptor for `delay(Long)` in eight lowered bodies.
The JVM, JS, Linux x64, Wasm/JS, and Wasm/WASI source archives also matched all five platform aliases at the pinned commit.

## Run the comparison

Use the Node and uv versions from the repository. Run these commands from `experiments/host-observation/promptfoo`.
Choose an absolute experiment directory outside the repository. Each coordinator needs a new output directory for its workspace.

```sh
npm ci
export EXPERIMENT="$(python3 -c 'from pathlib import Path; from tempfile import mkdtemp; print(Path(mkdtemp()).resolve())')"
export KAST_CORPUS_ROOT="$EXPERIMENT/workspaces"
export KAST_CORPUS_SKILL="$EXPERIMENT/skill/kast/SKILL.md"
mkdir -p "$EXPERIMENT/skill" "$EXPERIMENT/results"
uv run --locked --project .. python prepare_corpus.py "$KAST_CORPUS_ROOT"
kast install skill --output "$EXPERIMENT/skill"
KAST_CORPUS_CONTROLS=1 npm run eval -- -c corpus.config.mjs
```

Before the live runs, prepare each Host with a fresh query. Use the same Gradle home for preparation and the delegate.
Start with these declarations. Inspect the exact result, exhaustive coverage, failure count, and omission count.

```sh
export KAST_WORKSPACE_DEPENDENCY_SHARING=SHARED
kast query --workspace "$KAST_CORPUS_ROOT/ktfmt" --name FormatterContext --kind class --fields name,location --json
kast query --workspace "$KAST_CORPUS_ROOT/spring" --name BeanDefinitionDsl --kind class --fields name,location --json
```

For these two workspaces, run the coordinators with the same dependency settings. Use a fresh output directory for each repetition.

```sh
mise exec -- node corpus-coordinators.mjs ktfmt "$EXPERIMENT/results"
mise exec -- node corpus-coordinators.mjs spring "$EXPERIMENT/results"
mise exec -- node corpus-coordinators.mjs spring-holdout "$EXPERIMENT/results"
```

The `kotlinx.coroutines` build at the pinned commit lists a retired plugin repository. The plugin portal still serves its JMH plugin.
Use a separate Gradle home for this workspace. The init script removes that repository from plugin resolution for this build.
The full module list and source files stay intact. Use JDK 21 for Gradle and JDK 11 for upstream compile tasks.

```sh
export GRADLE_USER_HOME="$EXPERIMENT/coroutines-gradle"
export KAST_WORKSPACE_DEPENDENCY_SHARING=PRIVATE
export KAST_GRADLE_JAVA_HOME="$(mise where java@temurin-21)"
mkdir -p "$GRADLE_USER_HOME/init.d"
cp coroutines-import.gradle "$GRADLE_USER_HOME/init.d/"
# Set JDK11_HOME to the local JDK 11 installation.
echo "org.gradle.java.installations.paths=$JDK11_HOME" > "$GRADLE_USER_HOME/gradle.properties"
kast query --workspace "$KAST_CORPUS_ROOT/coroutines" --name CoroutineScheduler --kind class --fields name,location --json
mise exec -- node corpus-coordinators.mjs coroutines "$EXPERIMENT/results"
```

Each coordinator uses Sol 6.2 with high reasoning. Its only tool starts the fixed Promptfoo suite once.
Promptfoo starts a fresh pytest process for each arm. The harness examines the checkout before and after the isolated Pi session.
Luna 6, Terra 6.1, and Sol 6.2 each run at low reasoning. Each case has six trials.
The grader controls cover a correct set, a plausible wrong set, an omission, an extra member, and a duplicate.
Promptfoo exits with code 100 for wrong answers. Inspect the receipt for provider errors.

The report command reads all four exports from each run directory. It also accepts one suite directory. It examines the trial identities and grades the answers again.

```sh
node corpus-report.mjs "$EXPERIMENT/results" > "$EXPERIMENT/report.json"
```

After the last repetition, stop the three Hosts with `kast stop --workspace PATH`.

## Interpret the result

Compare arms within the same model, case, revision, and repetition. Count every planned trial, including timeouts and import failures.
Count a correct answer after a failed Host call as a correct answer. Credit compiler evidence only when the returned facts support that answer.
A declaration lookup alone cannot prove the callers of an overload.

Report correctness, the semantic evidence each participant received, and tool use. Compare token use only within pairs with two correct answers.
The suite supports a claim of differentiated value when a matched comparison shows a benefit on two distinct maintenance tasks with supporting compiler evidence.
One trial per arm gives a case result. A claim about reliability needs independent repetitions.

## Results on November 19, 2026

Terra 6.1 Low found every exact operator use in two repositories with Kast. It missed uses on both tasks in both source-only repetitions.
Sol 6.2 Low solved both tasks with either tool set. Luna 6 Low missed both tasks with either tool set.
Each row counts correct answers across three models and two repetitions. [Trial records](history/upstream-workspaces.json)

| Original comparison | Source | Source and Kast |
|---|---:|---:|
| Java `sync(int)` call sites | 3/6 | 5/6 |
| Kotlin constant-indent negation | 2/6 | 4/6 |
| Spring JDBC overloads | 6/6 | 6/6 |
| WebFlux primitive status callers | 6/6 | 4/6 |
| Spring route expressions, held out | 2/6 | 4/6 |
| Delay callers, Host import failed | 2/6 | 3/6 |
| Platform aliases, Host import failed | 6/6 | 6/6 |

In `ktfmt`, the exact `references` relation returned seven uses of `Indentation.Const.unaryMinus`. The `callers` relation returned zero rows for that override.
Terra used the references in both runs. Bytecode from all 131 compiled classes put the seven calls on the expected source lines.

The Spring holdout asked which string routes reach Java `RequestPredicates.path(String)` through the non-suspending DSL operator.
In both runs, the references included an ordinary `router` nested inside `CoRouterFunctionDslTests.kt`.
Terra found that site in both Kast runs. A bytecode scan of all 56 classes from the two upstream test files found three matching calls.
The holdout curator fixed the key after the first six cases ran and before a delegate saw the holdout.

Across 42 pairs, both arms were correct on 25. Only Kast passed six pairs. Only source passed one. Both missed eight. Two pairs had participant errors. Those pairs used 479,176 reported tokens with source and 675,241 with Kast.
The count includes cached input. The table denominators include the two errors.
One affected the Sol source arm on `sync(int)`. The other affected the Sol Kast arm on WebFlux status callers.
The corpus compares the installed skill and CLI with tracked-source search and reads. Other IDEs and language servers need their own comparisons.

A separate Luna trial on the Java call sites exercised the source path before the comparison. Its score stays outside the table.

Promptfoo 0.125.1 ran these trials with Pi 1.5.2 and Kast 0.0.4483 on macOS ARM64.
Both initial runs of `kotlinx.coroutines` used JDK 21 for Gradle. The upstream compile tasks also needed a JDK 11 toolchain.
Gradle completed its build, but the IDE rejected the import. No complete semantic receipt supports the initial coroutine answers.

With JDK 11.0.32.1 available to the compile tasks, Gradle and the IDE completed the full import.
Two fresh requests for `CoroutineScheduler` then returned `HOST_REJECTED/DEADLINE_EXCEEDED` at the VFS refresh stage.
The installed Host allowed ten seconds for that stage. These preflight requests produced no semantic results.
The recorded model trials used the initial import setup. The JDK 11 setup has no model score.
