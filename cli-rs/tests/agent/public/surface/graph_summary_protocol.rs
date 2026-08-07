use super::*;

#[test]
fn graph_summary_uses_the_canonical_deterministic_toon_protocol() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"kast-graph-summary\"\n",
    )
    .expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_public_graph(&workspace, false);
    let output = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
        .args(["graph", "summary"])
        .output()
        .expect("run kast graph summary");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(output.stderr.is_empty(), "{output:?}");
    let rendered = String::from_utf8(output.stdout).expect("UTF-8 graph summary");
    assert!(
        !rendered.trim_start().starts_with('{'),
        "graph summary must default to TOON: {rendered}"
    );
    let decoded: serde_json::Value =
        toon_format::decode_default(rendered.trim()).expect("graph summary is valid TOON");
    assert_eq!(decoded["schemaVersion"], 2, "{decoded:#}");
    assert_eq!(decoded["operation"], "graph.summary", "{decoded:#}");
    assert_eq!(decoded["status"], "complete", "{decoded:#}");
    assert_eq!(decoded["result"]["type"], "graph-summary", "{decoded:#}");
    assert_eq!(decoded["result"]["generation"], 41);
    assert_eq!(decoded["result"]["nodeCount"], 2);
    assert_eq!(decoded["result"]["edgeOccurrenceCount"], 2);
    assert_eq!(decoded["result"]["qualification"], "CURRENT");
    for private in ["ok", "method"] {
        assert!(
            decoded.get(private).is_none(),
            "graph summary leaked {private}: {decoded:#}"
        );
    }
}

#[test]
fn graph_summary_qualifies_stale_noncritical_persisted_facts() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_public_graph(&workspace, true);
    let output = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
        .args(["graph", "summary"])
        .output()
        .expect("run stale graph summary");

    assert!(output.status.success(), "{output:?}");
    let decoded: serde_json::Value =
        toon_format::decode_default(std::str::from_utf8(&output.stdout).expect("UTF-8").trim())
            .expect("qualified stale graph summary is valid TOON");
    assert_eq!(decoded["status"], "qualified", "{decoded:#}");
    assert_eq!(
        decoded["result"]["qualification"], "QUALIFIED",
        "{decoded:#}"
    );
    assert_eq!(decoded["result"]["coverage"]["stale"], 1, "{decoded:#}");
}

#[test]
fn graph_summary_ignores_current_reference_external_boundaries() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = seed_public_graph(&workspace, false);
    let failure_id = uuid::Uuid::new_v4().hyphenated().to_string();
    index
        .connection()
        .execute_batch(&format!(
            "UPDATE file_stage_outcomes
             SET outcome_status = 'EXTERNAL_BOUNDARY',
                 limitations_json = '[]',
                 failure_id = '{failure_id}',
                 failure_code = 'PSI_UNAVAILABLE',
                 failure_message = 'PSI is unavailable'
             WHERE stage = 'RELATIONSHIPS' AND filename = 'Source0000.kt';"
        ))
        .expect("external reference boundary");
    let output = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
        .args(["graph", "summary"])
        .output()
        .expect("run graph summary with an external reference boundary");

    assert!(output.status.success(), "{output:?}");
    let decoded: serde_json::Value =
        toon_format::decode_default(std::str::from_utf8(&output.stdout).expect("UTF-8").trim())
            .expect("current graph summary is valid TOON");
    assert_eq!(decoded["status"], "complete", "{decoded:#}");
    assert_eq!(decoded["result"]["qualification"], "CURRENT", "{decoded:#}");
    assert_eq!(decoded["result"]["coverage"]["limited"], 0, "{decoded:#}");
    assert_eq!(decoded["result"]["coverage"]["pending"], 0, "{decoded:#}");
    assert_eq!(decoded["result"]["nodeCount"], 2, "{decoded:#}");
}
