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
fn packaged_skill_teaches_kast_agent_as_exclusive_route() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let skill = std::fs::read_to_string(root.join("resources/kast-skill/SKILL.md"))
        .expect("packaged skill");

    for required in [
        "Use when working on Kotlin or Gradle semantics",
        "Route all Kast work through",
        "`kast agent ...` is the only first-class Kast",
        "Do not use raw transport",
        "If the active binary lacks",
        "require upgrade or reinstall",
        "do not replace the missing compiler-backed path with text search",
        "Route to the narrowest Kotlin-aware `kast agent` surface",
        "Keep using `kast agent` after the first successful call",
        "Normal installed use loads only `SKILL.md`",
        "Discover method schemas",
        "Do not pre-load the full source catalog",
        "Use `kast agent workflow ...` for repeated sequences",
        "`kast agent workflow ...`",
        "`kast agent call <method>`",
        "`kast agent tools`",
        "`kast agent workflow --help`",
        "`kast agent call symbol/scaffold`",
        "`kast agent call raw/file-outline`",
        "`kast agent call symbol/discover`",
        "`kast agent call symbol/resolve`",
        "`kast agent call symbol/references`",
        "`kast agent call symbol/callers`",
        "`kast agent call database/metrics`",
        "`kast agent workflow impact`",
        "`kast agent call raw/diagnostics`",
        "`kast agent call raw/workspace-search`",
        "workflow package-verify",
        "Use ordinary file tools for exact",
    ] {
        assert!(
            skill.contains(required),
            "packaged skill should teach {required}"
        );
    }

    for forbidden in [
        "kast rpc",
        "capabilities.experimental.kastMethods",
        "scripts/verify-kast-state.py",
        "scripts/kast-agent-call.py",
        "scripts/kast-semantic-workflow.py",
    ] {
        assert!(
            !skill.contains(forbidden),
            "packaged skill should not teach {forbidden}"
        );
    }
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
                    panic!(
                        "eval case {} should name a kast agent command instead of method {name}",
                        case["id"]
                    );
                }
                "tool" => {
                    panic!(
                        "eval case {} should name a kast agent command instead of tool {name}",
                        case["id"]
                    );
                }
                "command" => {
                    assert!(
                        name.starts_with("kast agent") || name.starts_with("kast setup"),
                        "eval case {} should use kast agent/setup commands, got {name}",
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
        "kast agent call symbol/scaffold",
        "kast agent call raw/file-outline",
        "kast agent call symbol/query",
        "kast agent call symbol/references",
        "kast agent call symbol/callers",
        "kast agent call raw/diagnostics",
        "kast agent call database/metrics",
        "kast agent workflow diagnostics",
        "kast agent workflow package-verify",
        "kast setup --force",
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
        "kast rpc",
        "generated protocol endpoints",
        "capabilities.experimental.kastMethods",
    ] {
        assert!(
            forbidden_names.contains(required),
            "routing eval should reject public API leak {required}"
        );
    }
}
