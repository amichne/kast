mod support;

use serde_json::Value;
use std::collections::BTreeSet;
use support::*;

fn assert_no_local_paths(value: &Value, label: &str) {
    match value {
        Value::String(text) => {
            for forbidden in ["/Users/", "/home/", "/private/", "C:\\"] {
                assert!(
                    !text.contains(forbidden),
                    "{label} should not contain local absolute path marker {forbidden}: {text}"
                );
            }
        }
        Value::Array(items) => {
            for item in items {
                assert_no_local_paths(item, label);
            }
        }
        Value::Object(fields) => {
            for value in fields.values() {
                assert_no_local_paths(value, label);
            }
        }
        _ => {}
    }
}

#[test]
fn packaged_skill_targets_rust_kast_only() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let skill = std::fs::read_to_string(root.join("resources/kast-skill/SKILL.md"))
        .expect("packaged skill");
    let quickstart =
        std::fs::read_to_string(root.join("resources/kast-skill/references/quickstart.md"))
            .expect("packaged skill quickstart");
    let workflows =
        std::fs::read_to_string(root.join("resources/kast-skill/references/workflows.md"))
            .expect("packaged skill workflows");
    let routing_reference = std::fs::read_to_string(
        root.join("resources/kast-skill/references/routing-improvement.md"),
    )
    .expect("routing reference");
    let instruction_cli = std::fs::read_to_string(root.join("resources/kast-instructions/cli.md"))
        .expect("portable CLI instructions");
    let instruction_tools =
        std::fs::read_to_string(root.join("resources/kast-instructions/tools.md"))
            .expect("portable tool instructions");

    assert!(skill.contains("Rust `kast` CLI"));
    assert!(skill.contains("command -v kast"));
    assert!(skill.contains("kast agent --help"));
    assert!(skill.contains("kast agent tools"));
    assert!(skill.contains("kast agent workflow --help"));
    assert!(skill.contains("kast --output json agent workflow package-verify"));
    assert!(skill.contains("kast agent call <method> --params-file"));
    assert!(!skill.contains("scripts/verify-kast-state.py"));
    assert!(!skill.contains("scripts/kast-agent-call.py"));
    assert!(!skill.contains("scripts/kast-semantic-workflow.py"));
    assert!(skill.contains("kast agent workflow ..."));
    assert!(skill.contains("Use for Gradle project file work"));
    assert!(skill.contains("assume the binary installed it"));
    assert!(skill.contains("`kast` directly"));
    assert!(skill.contains("active binary are incompatible"));
    assert!(skill.contains("project file operations"));
    assert!(skill.contains("Use Kast to discover the owning module"));
    assert!(skill.contains("when the path is not already exact"));
    assert!(skill.contains("Unknown symbol"));
    assert!(skill.contains("symbol/query"));
    assert!(skill.contains("raw/workspace-files"));
    assert!(skill.contains("includeFiles=false"));
    assert!(skill.contains("kast inspect metrics fan-in"));
    assert!(skill.contains("kast inspect demo"));
    assert!(skill.contains("raw/type-hierarchy"));
    assert!(skill.contains("raw/semantic-insertion-point"));
    assert!(skill.contains("raw/completions"));
    assert!(skill.contains("raw/apply-edits"));
    assert!(skill.contains("kast runtime up --workspace-root \"$PWD\" --backend idea"));
    assert!(skill.contains("Normal installed use loads only `SKILL.md`"));
    assert!(skill.contains("Discover available methods"));
    assert!(!skill.contains("references/commands.json"));
    assert!(!skill.contains("references/requests/"));
    assert!(quickstart.contains("command -v kast"));
    assert!(quickstart.contains("kast agent --help"));
    assert!(quickstart.contains("kast agent tools"));
    assert!(quickstart.contains("kast agent workflow --help"));
    assert!(quickstart.contains("kast agent call"));
    assert!(quickstart.contains("kast --output json agent workflow package-verify"));
    assert!(quickstart.contains("result.invocation.argv"));
    assert!(quickstart.contains("schemaVersion >= 3"));
    assert!(quickstart.contains("matching `toolCount`"));
    assert!(!quickstart.contains("scripts/verify-kast-state.py"));
    assert!(!quickstart.contains("scripts/kast-agent-call.py"));
    assert!(!quickstart.contains("scripts/kast-semantic-workflow.py"));
    assert!(quickstart.contains("active binary are incompatible"));
    assert!(quickstart.contains("incompatible. Upgrade or reinstall Kast"));
    assert!(!quickstart.contains("raw transport/debug escape hatch"));
    assert!(quickstart.contains("kast inspect metrics impact"));
    assert!(quickstart.contains("kast inspect demo"));
    assert!(quickstart.contains("INDEX_UNAVAILABLE"));
    assert!(quickstart.contains("kast runtime up --workspace-root \"$PWD\" --backend idea"));
    assert!(quickstart.contains("follow its recovery commands"));
    assert!(quickstart.contains("preserve the selected executable token"));
    assert!(quickstart.contains("--skill-target-dir"));
    assert!(quickstart.contains("--instructions-target-dir"));
    assert!(workflows.contains("Execute recovery commands exactly as emitted"));
    assert!(workflows.contains("--copilot-target-dir"));
    assert!(workflows.contains("--skill-target-dir"));
    assert!(workflows.contains("--instructions-target-dir"));
    assert!(workflows.contains("nextCommandArgv"));
    assert!(workflows.contains("kast agent call <method> --params-file"));
    assert!(!workflows.contains("scripts/verify-kast-state.py"));
    assert!(!workflows.contains("scripts/kast-agent-call.py"));
    assert!(routing_reference.contains("fixtures/maintenance/evals/routing.json"));
    assert!(routing_reference.contains("fixtures/maintenance/evals/routing.schema.json"));
    assert!(routing_reference.contains("allowedActions"));
    assert!(instruction_cli.contains("kast agent tools"));
    assert!(instruction_cli.contains("kast agent workflow --help"));
    assert!(instruction_cli.contains("stale instruction/binary install"));
    assert!(instruction_cli.contains("every `.kt` and `.kts` file"));
    assert!(instruction_cli.contains("Do not invoke Kast for unrelated docs/text work"));
    assert!(instruction_tools.contains("kast agent tools"));
    assert!(instruction_tools.contains("--copilot-target-dir"));
    assert!(instruction_tools.contains("nextCommandArgv"));
    assert!(instruction_tools.contains("result.invocation.argv"));
    assert!(instruction_tools.contains("schemaVersion >= 3"));
    assert!(instruction_tools.contains("matching `toolCount`"));
    assert!(instruction_tools.contains("Keep using Kast after the first successful call"));
    assert!(
        !root.join("resources/kast-instructions/rpc.md").exists(),
        "installable instructions must not ship a raw RPC guide"
    );
    assert!(
        root.join("resources/kast-skill/references/workflows.md")
            .is_file(),
        "source skill tree must include workflow ownership reference"
    );
    assert!(
        root.join("resources/kast-skill/fixtures/maintenance/evals/routing.schema.json")
            .is_file(),
        "packaged skill must include a schema for routing evals"
    );
    assert!(
        !root
            .join("resources/kast-skill/scripts/kast-semantic-workflow.py")
            .exists(),
        "semantic workflow runner must live in the active kast binary"
    );

    assert!(
        root.join("resources/plugin/lsp.json").is_file(),
        "packaged Copilot LSP plugin source must live under cli-rs/resources/plugin"
    );
}

#[test]
fn repo_local_copilot_plugin_content_is_generated_not_tracked() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repo root");

    assert!(
        root.join("cli-rs/resources/plugin/plugin.json").is_file(),
        "repo-local plugin source must live under cli-rs/resources/plugin"
    );
}

#[test]
fn packaged_skill_routing_eval_covers_kotlin_navigation_surface() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let skill = std::fs::read_to_string(root.join("resources/kast-skill/SKILL.md"))
        .expect("packaged skill");
    let routing_eval_path =
        root.join("resources/kast-skill/fixtures/maintenance/evals/routing.json");
    let routing_eval: Value = serde_json::from_str(
        &std::fs::read_to_string(&routing_eval_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", routing_eval_path.display())),
    )
    .expect("routing eval json");
    let routing_schema_path =
        root.join("resources/kast-skill/fixtures/maintenance/evals/routing.schema.json");
    let routing_schema: Value = serde_json::from_str(
        &std::fs::read_to_string(&routing_schema_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", routing_schema_path.display())),
    )
    .expect("routing eval schema json");
    let routing_validator = jsonschema::validator_for(&routing_schema).expect("routing schema");
    routing_validator
        .validate(&routing_eval)
        .unwrap_or_else(|error| panic!("routing eval schema validation failed: {error}"));
    let catalog: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/commands.json"
    ))
    .expect("commands catalog");
    let commands = catalog["commands"].as_object().expect("catalog commands");
    let tool_names = commands
        .values()
        .filter_map(|command| command.get("tool"))
        .map(|tool| tool["name"].as_str().expect("tool name"))
        .collect::<BTreeSet<_>>();
    let cases = routing_eval["cases"]
        .as_array()
        .expect("routing eval cases");
    assert!(
        cases.len() >= 10,
        "routing eval should cover initial pickup, continuous use, recovery, efficiency, negative routing, and public API boundaries"
    );

    let case_ids = cases
        .iter()
        .map(|case| case["id"].as_str().expect("case id"))
        .collect::<BTreeSet<_>>();
    for required in [
        "kotlin-file-trigger-all-kt-kts",
        "unknown-symbol-navigation",
        "relationship-navigation",
        "source-index-database-access",
        "agent-workflow-public-surface",
        "continuous-kast-use-after-first-call",
        "source-override-skill-recovery",
        "reference-budget-symbol-query",
        "non-kotlin-docs-negative-case",
        "public-api-boundary",
    ] {
        assert!(
            case_ids.contains(required),
            "routing eval should include {required}"
        );
    }

    for case in cases {
        assert_no_local_paths(case, case["id"].as_str().expect("case id"));
        let expects_kast = case["expectedPrimitive"]["name"] == "kast";
        let expects_none = case["expectedPrimitive"]["name"] == "none";
        assert!(
            expects_kast || expects_none,
            "case should route to Kast or explicitly expect no primitive: {case:#}"
        );
        let forbidden = case["forbiddenActions"]
            .as_array()
            .unwrap_or_else(|| panic!("case {} should list forbidden fallbacks", case["id"]));
        if expects_kast {
            assert!(
                forbidden.iter().any(|value| value == "grep")
                    && forbidden.iter().any(|value| value == "rg"),
                "case {} should forbid raw text search for Kotlin semantics",
                case["id"]
            );
        } else {
            assert_eq!(
                case["type"], "OVER_TRIGGER",
                "negative case should use OVER_TRIGGER: {case:#}"
            );
            assert!(
                forbidden.iter().any(|value| value == "kast agent workflow")
                    && forbidden.iter().any(|value| value == "symbol/query"),
                "negative case {} should forbid Kast semantic routing",
                case["id"]
            );
        }
        assert!(
            case["verificationEvidence"]
                .as_array()
                .expect("verification evidence")
                .len()
                >= 2,
            "case {} should include concrete verification evidence",
            case["id"]
        );
        for action in case["allowedActions"]
            .as_array()
            .unwrap_or_else(|| panic!("case {} should list allowed actions", case["id"]))
        {
            let kind = action["kind"].as_str().expect("action kind");
            let name = action["name"].as_str().expect("action name");
            match kind {
                "method" => {
                    let command = commands.get(name).unwrap_or_else(|| {
                        panic!("eval case {} references missing method {name}", case["id"])
                    });
                    if matches!(
                        name,
                        "symbol/query"
                            | "symbol/scaffold"
                            | "symbol/resolve"
                            | "symbol/references"
                            | "symbol/callers"
                            | "database/metrics"
                            | "raw/file-outline"
                    ) {
                        assert!(
                            command.get("tool").is_some(),
                            "eval case {} expects {name} to be available through agent tools",
                            case["id"]
                        );
                    }
                }
                "tool" => {
                    assert!(
                        tool_names.contains(name),
                        "eval case {} references missing tool {name}",
                        case["id"]
                    );
                }
                "command" => {
                    assert!(
                        name.starts_with("kast agent") || name.starts_with("kast inspect metrics"),
                        "eval case {} should use public Kast commands, got {name}",
                        case["id"]
                    );
                }
                "generic" => {
                    assert!(
                        expects_none,
                        "eval case {} should use generic actions only for negative cases",
                        case["id"]
                    );
                }
                other => panic!("unexpected action kind {other}"),
            }
        }
    }
    let action_names = cases
        .iter()
        .flat_map(|case| {
            case["allowedActions"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|action| action["name"].as_str().expect("action name"))
        })
        .collect::<BTreeSet<_>>();
    for required in [
        "symbol/query",
        "symbol/callers",
        "database/metrics",
        "kast agent workflow diagnostics",
        "kast agent workflow package-verify",
        "kast agent setup skill --source-dir",
        "kast agent tools",
    ] {
        assert!(
            action_names.contains(required),
            "routing eval should cover public action {required}"
        );
    }
    let forbidden_names = cases
        .iter()
        .flat_map(|case| {
            case["forbiddenActions"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|action| action.as_str().expect("forbidden action"))
        })
        .collect::<BTreeSet<_>>();
    for required in [
        "generated protocol endpoints",
        "capabilities.experimental.kastMethods",
    ] {
        assert!(
            forbidden_names.contains(required),
            "routing eval should reject public API leak {required}"
        );
    }

    assert!(
        (skill.contains(".kt") || skill.contains("`.kt`"))
            && (skill.contains(".kts") || skill.contains("`.kts`")),
        "skill trigger text must explicitly cover Kotlin source and script files"
    );
    assert!(
        skill.contains("only navigation surface"),
        "skill should state that Kast can be the sole navigation surface"
    );
    assert!(
        !skill.contains("/rpc/") && !skill.contains("capabilities.experimental.kastMethods"),
        "skill must not teach generated protocol endpoints or LSP internals as the public API"
    );
    assert!(
        skill.contains("Keep using Kast after the first successful call")
            && skill.contains("A first Kast result is not a handoff back to generic file reads"),
        "skill should keep follow-up Kotlin work on Kast after initial pickup"
    );
    assert!(
        skill.contains("Normal use loads only SKILL.md")
            || skill.contains("Normal installed use loads only `SKILL.md`"),
        "skill should keep normal installed use to the entrypoint"
    );
    assert!(
        skill.contains("Do not pre-load the full catalog")
            && skill.contains("Discover available methods")
            && skill.contains("not part of the installed skill payload"),
        "skill should use CLI-owned progressive disclosure instead of installed references"
    );
}
