use super::*;

#[test]
fn graph_continuations_reject_stale_generations() {
    let fixture = tempfile::tempdir().expect("temporary stale graph fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_paged_public_graph(&workspace);

    let first = published_public_kast(&home, &config_home, &workspace)
        .current_dir(&workspace)
        .args(["--output", "json", "graph", "nodes"])
        .output()
        .expect("first graph page");
    assert!(first.status.success(), "{first:?}");
    let first: serde_json::Value =
        serde_json::from_slice(&first.stdout).expect("first graph page JSON");
    let continuation = first["result"]["page"]["continuation"]
        .as_str()
        .expect("graph continuation");
    let mut fields = continuation
        .split('.')
        .map(str::to_string)
        .collect::<Vec<_>>();
    assert_eq!(fields.len(), 4, "{continuation}");
    assert_eq!(fields[0], "kgn3", "{continuation}");
    fields[2] = fields[2]
        .parse::<u64>()
        .expect("graph generation")
        .checked_add(1)
        .expect("next graph generation")
        .to_string();
    let stale = fields.join(".");

    let rejected = published_public_kast(&home, &config_home, &workspace)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "graph",
            "nodes",
            "--continuation",
            &stale,
        ])
        .output()
        .expect("stale graph page");
    assert_eq!(rejected.status.code(), Some(1), "{rejected:?}");
    let rejected: serde_json::Value =
        serde_json::from_slice(&rejected.stdout).expect("stale graph rejection JSON");
    assert_eq!(rejected["operation"], "graph.nodes", "{rejected:#}");
    assert_eq!(rejected["status"], "rejected", "{rejected:#}");
    assert_eq!(rejected["result"]["type"], "rejected", "{rejected:#}");
    assert_eq!(
        rejected["result"]["failure"]["code"], "GRAPH_PAGE_EXPIRED",
        "{rejected:#}"
    );
}
