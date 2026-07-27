#[test]
fn agent_repository_exposes_expect_actual_relation() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_expect_actual_relationship(&fixture);
    let workspace_root = workspace.to_str().expect("workspace");

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace_root,
            "--question",
            "Show outgoing EXPECT_ACTUAL relationships from PlatformClock.",
            "--intent",
            "outgoing-impact",
            "--relation",
            "expect-actual",
            "--max-depth",
            "1",
            "--depth",
            "1",
            "--results",
            "10",
            "--evidence",
            "2",
        ])
        .output()
        .expect("agent repository expect/actual relation");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let compact_raw = String::from_utf8(output.stdout).expect("compact UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact repository TOON");

    let help = kast(&home, &config_home)
        .args(["agent", "repository", "--help"])
        .output()
        .expect("agent repository help");
    let help_stdout = String::from_utf8(help.stdout).expect("help UTF-8");

    assert_eq!(
        serde_json::json!({
            "helpAdvertisesRelation": help.status.success()
                && help_stdout.contains("expect-actual"),
            "status": compact["result"]["status"],
            "relationships": compact["result"]["relationships"]
                .as_array()
                .expect("compact relationships")
                .iter()
                .map(|relationship| serde_json::json!({
                    "sourceKey": relationship["sourceKey"],
                    "targetKey": relationship["targetKey"],
                    "kind": relationship["kind"],
                    "evidenceClass": relationship["evidenceClass"]
                }))
                .collect::<Vec<_>>()
        }),
        serde_json::json!({
            "helpAdvertisesRelation": true,
            "status": "ANSWERED",
            "relationships": [{
                "sourceKey": "class:actual:PlatformClock",
                "targetKey": "class:expect:CommonClock",
                "kind": "EXPECT_ACTUAL",
                "evidenceClass": "compiler"
            }]
        }),
        "help={help_stdout}\ncompact={compact:#}"
    );
}
