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
fn packaged_skill_stays_usage_first_and_public_agent_only() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let skill_path = root.join("resources/kast-skill/SKILL.md");
    let skill = std::fs::read_to_string(&skill_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", skill_path.display()));

    assert!(
        skill.contains("Use `kast agent` before generic file reads"),
        "{skill}"
    );
    assert!(
        skill.contains("`kast agent symbol --query <name> --workspace-root \"$PWD\"`"),
        "{skill}"
    );
    assert!(
        skill.contains(
            "`kast agent rename --symbol <fq-name> --new-name <name> --workspace-root \"$PWD\"`"
        ),
        "{skill}"
    );
    assert!(
        skill.contains("`kast repair --for agent|kotlin|release|machine"),
        "{skill}"
    );
    assert!(
        skill.contains("`--output json` for JSON-only parsed scripts"),
        "{skill}"
    );
    assert!(skill.contains("read-only readiness"), "{skill}");
    assert!(
        skill.contains("Do not teach `kast agent tools`, `kast agent call`, `kast agent workflow`"),
        "{skill}"
    );
    assert!(
        !skill.contains("Use `kast agent workflow"),
        "workflow should not be a positive route: {skill}"
    );
    assert!(
        !skill.contains("Use `kast agent call"),
        "agent call should not be a positive route: {skill}"
    );
    assert!(
        !skill.contains("`kast agent scaffold`"),
        "hidden aliases should not be installed as the primary skill route: {skill}"
    );
    assert!(
        !skill.contains("`kast agent raw-"),
        "raw hidden aliases should not be installed as the primary skill route: {skill}"
    );
    assert!(
        !skill.contains("| Need | Use |"),
        "installed skill should stay thin instead of shipping a bulky route table: {skill}"
    );
    assert!(
        skill.lines().count() <= 70,
        "installed skill should stay thin: {} lines",
        skill.lines().count()
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
                        name.starts_with("kast agent"),
                        "eval case {} should use kast agent commands, got {name}",
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
        "kast agent call symbol/discover",
        "kast agent call symbol/resolve",
        "kast agent call symbol/references",
        "kast agent call symbol/callers",
        "kast agent call raw/diagnostics",
        "kast agent call database/metrics",
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
        "kast rpc",
        "generated protocol endpoints",
        "capabilities.experimental.kastMethods",
    ] {
        assert!(
            forbidden_names.contains(required),
            "routing eval should reject public API leak {required}"
        );
    }

    let repo_root = root.parent().expect("repo root");
    let routing_script_path = repo_root.join(".github/scripts/test-kast-routing-evals.sh");
    let routing_script = std::fs::read_to_string(&routing_script_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", routing_script_path.display()));
    assert!(
        routing_script.contains("--output json agent tools --full"),
        "routing metric-pack input should request JSON explicitly while implicit noninteractive output defaults to TOON: {routing_script}"
    );
}

#[test]
fn packaged_skill_format_impact_eval_covers_toon_accuracy_surface() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let eval_path = root.join("resources/kast-skill/fixtures/maintenance/evals/format-impact.json");
    let eval: Value = serde_json::from_str(
        &std::fs::read_to_string(&eval_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", eval_path.display())),
    )
    .expect("format impact eval json");
    let schema_path =
        root.join("resources/kast-skill/fixtures/maintenance/evals/format-impact.schema.json");
    let schema: Value = serde_json::from_str(
        &std::fs::read_to_string(&schema_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", schema_path.display())),
    )
    .expect("format impact eval schema json");
    let validator = jsonschema::validator_for(&schema).expect("format impact schema");
    validator
        .validate(&eval)
        .unwrap_or_else(|error| panic!("format impact eval schema validation failed: {error}"));

    assert_eq!(eval["schemaVersion"], 1, "{eval:#}");
    assert_eq!(
        eval["formats"],
        serde_json::json!(["json", "toon"]),
        "{eval:#}"
    );

    let cases = eval["cases"].as_array().expect("format impact eval cases");
    assert!(
        cases.len() >= 7,
        "format impact eval should cover tool catalog, symbol extraction, relationship navigation, validation recovery, workflow evidence, negative routing, and large read-only output"
    );

    let case_ids = cases
        .iter()
        .map(|case| case["id"].as_str().expect("case id"))
        .collect::<BTreeSet<_>>();
    for required in [
        "tool-catalog-comprehension",
        "symbol-result-extraction",
        "relationship-navigation-continuation",
        "validation-error-recovery",
        "workflow-evidence-json-artifacts",
        "non-kotlin-negative-routing",
        "large-read-only-catalog-efficiency",
    ] {
        assert!(
            case_ids.contains(required),
            "format impact eval should include {required}"
        );
    }

    for case in cases {
        assert_no_local_paths(case, case["id"].as_str().expect("case id"));
        assert_eq!(case["pairedFormats"], eval["formats"], "{case:#}");
        assert!(
            case["prompt"]
                .as_str()
                .is_some_and(|prompt| !prompt.is_empty()),
            "case should include a prompt: {case:#}"
        );
        assert!(
            case["goldFacts"]
                .as_array()
                .is_some_and(|facts| facts.len() >= 2),
            "case should include gold facts: {case:#}"
        );
        assert!(
            case["answerScoring"]["requiredTerms"]
                .as_array()
                .is_some_and(|terms| !terms.is_empty()),
            "case should include deterministic answer scoring terms: {case:#}"
        );
        assert!(
            case["answerScoring"]["forbiddenTerms"].is_array(),
            "case should include deterministic forbidden answer terms: {case:#}"
        );
        assert!(
            case["reportOnly"].as_bool() == Some(true),
            "format impact live accuracy cases must stay report-only: {case:#}"
        );

        let expected_actions = case["expectedActions"]
            .as_array()
            .unwrap_or_else(|| panic!("case {} should list expected actions", case["id"]));
        let forbidden_actions = case["forbiddenActions"]
            .as_array()
            .unwrap_or_else(|| panic!("case {} should list forbidden actions", case["id"]));
        if case["expectedPrimitive"]["name"] == "none" {
            assert!(
                expected_actions
                    .iter()
                    .all(|action| action["kind"] == "generic"),
                "negative format-impact cases should not expect Kast actions: {case:#}"
            );
            assert!(
                forbidden_actions
                    .iter()
                    .any(|action| action == "kast agent call"),
                "negative format-impact cases should forbid Kast calls: {case:#}"
            );
        } else {
            assert!(
                expected_actions.iter().any(|action| {
                    action["kind"] == "command"
                        && action["name"]
                            .as_str()
                            .is_some_and(|name| name.starts_with("kast agent"))
                }),
                "positive format-impact cases should expect a kast agent command: {case:#}"
            );
            assert!(
                forbidden_actions.iter().any(|action| action == "grep")
                    && forbidden_actions.iter().any(|action| action == "rg"),
                "positive format-impact cases should forbid text-search fallbacks: {case:#}"
            );
        }
    }
}

#[test]
fn format_impact_metric_pack_and_runner_capture_scoreable_answers() {
    let repo_root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repo root");
    let manifest_path = repo_root.join(".github/plugin-eval/kast-format-impact/manifest.json");
    let manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(&manifest_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", manifest_path.display())),
    )
    .expect("format impact metric pack manifest");

    assert_eq!(manifest["name"], "kast-format-impact", "{manifest:#}");
    assert_eq!(
        manifest["command"],
        serde_json::json!(["node", "./emit-kast-format-impact-metrics.mjs"]),
        "{manifest:#}"
    );

    let runner_path = repo_root.join(".github/scripts/run-kast-format-impact-report.sh");
    let runner = std::fs::read_to_string(&runner_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", runner_path.display()));
    assert!(
        runner.contains("format_impact_report"),
        "runner should use the Rust TOON-aware report generator: {runner}"
    );
    assert!(
        runner.contains("KAST_FORMAT_IMPACT_OBSERVED_JSONL"),
        "runner should feed observed JSONL into the metric pack: {runner}"
    );
    assert!(
        runner.contains("--answer-requests"),
        "runner should write answer request JSONL for external answer capture: {runner}"
    );
    assert!(
        runner.contains("--suite format-impact"),
        "runner should select the format-impact comparison suite explicitly: {runner}"
    );
    assert!(
        runner.contains("--agent-output-shape")
            && runner.contains("KAST_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE")
            && runner.contains("KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE")
            && runner.contains("text|json|toon"),
        "runner should make external agent answer shape configurable: {runner}"
    );
    assert!(
        runner.contains("KAST_FORMAT_IMPACT_ANSWERS_JSONL"),
        "runner should score captured answers when supplied: {runner}"
    );

    let metric_pack_path = repo_root
        .join(".github/plugin-eval/kast-format-impact/emit-kast-format-impact-metrics.mjs");
    let metric_pack = std::fs::read_to_string(&metric_pack_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", metric_pack_path.display()));
    assert!(
        metric_pack.contains("format-impact-answer-scoring"),
        "metric pack should expose answer scoring as a first-class check: {metric_pack}"
    );
    assert!(
        metric_pack.contains("kast-format-impact-answer-pass-rate"),
        "metric pack should emit answer pass-rate metrics: {metric_pack}"
    );

    let target = repo_root.join("cli-rs/resources/kast-skill");
    let eval_path = target.join("fixtures/maintenance/evals/format-impact.json");
    let eval: Value = serde_json::from_str(
        &std::fs::read_to_string(&eval_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", eval_path.display())),
    )
    .expect("format impact eval json");
    let temp = tempfile::tempdir().expect("format impact metric tempdir");
    let observed_path = temp.path().join("observed.jsonl");
    let mut observed = String::new();
    for case in eval["cases"].as_array().expect("format impact cases") {
        let case_id = case["id"].as_str().expect("case id");
        for format in ["json", "toon"] {
            observed.push_str(
                &serde_json::to_string(&serde_json::json!({
                    "caseId": case_id,
                    "format": format,
                    "decodedEquivalent": true,
                    "answerVerdict": "pass",
                    "stdoutBytes": 1
                }))
                .expect("observed record json"),
            );
            observed.push('\n');
        }
    }
    std::fs::write(&observed_path, observed)
        .unwrap_or_else(|error| panic!("write {}: {error}", observed_path.display()));

    let output = Command::new("node")
        .arg(&metric_pack_path)
        .arg(&target)
        .arg("skill")
        .env("KAST_FORMAT_IMPACT_OBSERVED_JSONL", &observed_path)
        .output()
        .unwrap_or_else(|error| panic!("run {}: {error}", metric_pack_path.display()));
    assert!(
        output.status.success(),
        "metric pack should run: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let metric_output: Value =
        serde_json::from_slice(&output.stdout).expect("metric pack output json");
    let answer_check = metric_output["checks"]
        .as_array()
        .expect("metric checks")
        .iter()
        .find(|check| check["id"] == "format-impact-answer-scoring")
        .expect("answer scoring check");
    assert_eq!(answer_check["status"], "pass", "{metric_output:#}");
    let answer_pass_rate = metric_output["metrics"]
        .as_array()
        .expect("metric list")
        .iter()
        .find(|metric| metric["id"] == "kast-format-impact-answer-pass-rate")
        .expect("answer pass-rate metric");
    assert_eq!(answer_pass_rate["value"], 100, "{metric_output:#}");
}

#[test]
fn routing_format_impact_metric_pack_and_runner_capture_scoreable_answers() {
    let repo_root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repo root");
    let manifest_path =
        repo_root.join(".github/plugin-eval/kast-routing-format-impact/manifest.json");
    let manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(&manifest_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", manifest_path.display())),
    )
    .expect("routing format impact metric pack manifest");

    assert_eq!(
        manifest["name"], "kast-routing-format-impact",
        "{manifest:#}"
    );
    assert_eq!(
        manifest["command"],
        serde_json::json!(["node", "./emit-kast-routing-format-impact-metrics.mjs"]),
        "{manifest:#}"
    );

    let runner_path = repo_root.join(".github/scripts/run-kast-routing-format-impact-report.sh");
    let runner = std::fs::read_to_string(&runner_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", runner_path.display()));
    assert!(
        runner.contains("format_impact_report"),
        "runner should use the shared Rust JSON/TOON report generator: {runner}"
    );
    assert!(
        runner.contains("--suite routing"),
        "runner should select the routing comparison suite: {runner}"
    );
    assert!(
        runner.contains("--agent-output-shape")
            && runner.contains("KAST_ROUTING_FORMAT_IMPACT_AGENT_OUTPUT_SHAPE")
            && runner.contains("KAST_SKILL_EVAL_AGENT_OUTPUT_SHAPE")
            && runner.contains("text|json|toon"),
        "runner should make routing agent answer shape configurable: {runner}"
    );
    assert!(
        runner.contains("KAST_ROUTING_FORMAT_IMPACT_OBSERVED_JSONL"),
        "runner should feed observed JSONL into the routing metric pack: {runner}"
    );
    assert!(
        runner.contains("KAST_ROUTING_FORMAT_IMPACT_ANSWERS_JSONL"),
        "runner should score captured routing answers when supplied: {runner}"
    );

    let combined_path = repo_root.join(".github/scripts/run-kast-skill-eval-format-comparison.sh");
    let combined = std::fs::read_to_string(&combined_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", combined_path.display()));
    assert!(
        combined.contains("run-kast-format-impact-report.sh")
            && combined.contains("run-kast-routing-format-impact-report.sh"),
        "combined runner should execute both JSON/TOON comparison suites: {combined}"
    );

    let metric_pack_path = repo_root.join(
        ".github/plugin-eval/kast-routing-format-impact/emit-kast-routing-format-impact-metrics.mjs",
    );
    let metric_pack = std::fs::read_to_string(&metric_pack_path)
        .unwrap_or_else(|error| panic!("read {}: {error}", metric_pack_path.display()));
    assert!(
        metric_pack.contains("routing-format-impact-answer-scoring"),
        "metric pack should expose answer scoring as a first-class check: {metric_pack}"
    );
    assert!(
        metric_pack.contains("kast-routing-format-impact-answer-pass-rate"),
        "metric pack should emit answer pass-rate metrics: {metric_pack}"
    );

    let target = repo_root.join("cli-rs/resources/kast-skill");
    let routing_path = target.join("fixtures/maintenance/evals/routing.json");
    let routing: Value = serde_json::from_str(
        &std::fs::read_to_string(&routing_path)
            .unwrap_or_else(|error| panic!("read {}: {error}", routing_path.display())),
    )
    .expect("routing eval json");
    let temp = tempfile::tempdir().expect("routing format impact metric tempdir");
    let observed_path = temp.path().join("observed.jsonl");
    let mut observed = String::new();
    for case in routing["cases"].as_array().expect("routing cases") {
        let case_id = case["id"].as_str().expect("case id");
        let input = serde_json::json!({
            "suite": "routing",
            "case": case,
        });
        let mut json_input = serde_json::to_string_pretty(&input).expect("json input");
        json_input.push('\n');
        let toon_input = toon_format::encode_default(&input).expect("toon input");
        assert!(
            serde_json::from_str::<Value>(&toon_input).is_err(),
            "routing TOON input should not parse as JSON for {case_id}"
        );
        let decoded: Value =
            toon_format::decode_default(toon_input.trim()).expect("decode routing toon");
        assert_eq!(
            decoded, input,
            "routing TOON should decode to JSON for {case_id}"
        );

        for (format, stdout_bytes) in [("json", json_input.len()), ("toon", toon_input.len())] {
            observed.push_str(
                &serde_json::to_string(&serde_json::json!({
                    "caseId": case_id,
                    "format": format,
                    "decodedEquivalent": true,
                    "answerVerdict": "pass",
                    "stdoutBytes": stdout_bytes
                }))
                .expect("observed record json"),
            );
            observed.push('\n');
        }
    }
    std::fs::write(&observed_path, observed)
        .unwrap_or_else(|error| panic!("write {}: {error}", observed_path.display()));

    let output = Command::new("node")
        .arg(&metric_pack_path)
        .arg(&target)
        .arg("skill")
        .env("KAST_ROUTING_FORMAT_IMPACT_OBSERVED_JSONL", &observed_path)
        .output()
        .unwrap_or_else(|error| panic!("run {}: {error}", metric_pack_path.display()));
    assert!(
        output.status.success(),
        "metric pack should run: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let metric_output: Value =
        serde_json::from_slice(&output.stdout).expect("metric pack output json");
    let answer_check = metric_output["checks"]
        .as_array()
        .expect("metric checks")
        .iter()
        .find(|check| check["id"] == "routing-format-impact-answer-scoring")
        .expect("answer scoring check");
    assert_eq!(answer_check["status"], "pass", "{metric_output:#}");
    let answer_pass_rate = metric_output["metrics"]
        .as_array()
        .expect("metric list")
        .iter()
        .find(|metric| metric["id"] == "kast-routing-format-impact-answer-pass-rate")
        .expect("answer pass-rate metric");
    assert_eq!(answer_pass_rate["value"], 100, "{metric_output:#}");
}
