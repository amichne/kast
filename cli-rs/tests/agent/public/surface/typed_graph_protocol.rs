use super::*;

const MAX_PUBLIC_GRAPH_RESPONSE_BYTES: usize = 64 * 1_024;

#[test]
fn large_graph_results_are_bounded_stable_and_snapshot_bound() {
    let fixture = tempfile::tempdir().expect("temporary large graph fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = seed_large_public_graph(&workspace);

    for (operation, expected_count_field) in
        [("topology", "nodeCount"), ("communities", "nodeCount")]
    {
        let run = || {
            published_public_kast(&home, &config_home, &workspace)
                .current_dir(&workspace)
                .args(["--output", "json", "graph", operation])
                .output()
                .unwrap_or_else(|error| panic!("run graph {operation}: {error}"))
        };
        let first = run();
        assert!(first.status.success(), "{operation}: {first:?}");
        assert!(
            first.stdout.len() <= MAX_PUBLIC_GRAPH_RESPONSE_BYTES,
            "graph {operation} emitted {} bytes",
            first.stdout.len()
        );
        let first_value: serde_json::Value =
            serde_json::from_slice(&first.stdout).expect("bounded graph summary JSON");
        assert_eq!(
            first_value["result"]["summary"]["type"], "bounded-summary",
            "{first_value:#}"
        );
        assert_eq!(
            first_value["result"]["summary"][expected_count_field], 2_049,
            "{first_value:#}"
        );

        let second = run();
        assert!(second.status.success(), "{operation}: {second:?}");
        assert_eq!(
            first.stdout, second.stdout,
            "unstable graph {operation} summary"
        );
    }

    let mut continuation = None;
    let mut first_continuation = None;
    let mut first_selector = None;
    let mut observed_ids = Vec::new();
    loop {
        let mut command = published_public_kast(&home, &config_home, &workspace);
        command
            .current_dir(&workspace)
            .args(["--output", "json", "graph", "nodes"]);
        if let Some(token) = continuation.as_deref() {
            command.args(["--continuation", token]);
        }
        let output = command.output().expect("bounded graph node page");
        assert!(output.status.success(), "{output:?}");
        assert!(
            output.stdout.len() <= MAX_PUBLIC_GRAPH_RESPONSE_BYTES,
            "graph nodes emitted {} bytes",
            output.stdout.len()
        );
        let value: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("graph node page JSON");
        let nodes = value["result"]["nodes"].as_array().expect("graph nodes");
        if first_selector.is_none() {
            first_selector = nodes[0]["nodeSelector"].as_str().map(str::to_string);
        }
        observed_ids.extend(
            nodes
                .iter()
                .map(|node| node["id"].as_u64().expect("numeric graph node identity")),
        );
        continuation = value["result"]["page"]["continuation"]
            .as_str()
            .map(str::to_string);
        first_continuation = first_continuation.or_else(|| continuation.clone());
        if continuation.is_none() {
            break;
        }
        assert!(observed_ids.len() <= 2_049, "graph continuation cycle");
    }
    assert_eq!(observed_ids, (1_u64..=2_049).collect::<Vec<_>>());

    let neighbors = published_public_kast(&home, &config_home, &workspace)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "graph",
            "neighbors",
            "--node-selector",
            first_selector
                .as_deref()
                .expect("first graph node selector"),
        ])
        .output()
        .expect("bounded graph neighbor summary");
    assert!(neighbors.status.success(), "{neighbors:?}");
    assert!(neighbors.stdout.len() <= MAX_PUBLIC_GRAPH_RESPONSE_BYTES);
    let neighbors: serde_json::Value =
        serde_json::from_slice(&neighbors.stdout).expect("graph neighbor summary JSON");
    assert_eq!(
        neighbors["result"]["summary"],
        serde_json::json!({
            "type": "bounded-summary",
            "incomingCount": 0,
            "outgoingCount": 2_049
        }),
        "{neighbors:#}"
    );

    index
        .connection()
        .execute("UPDATE schema_version SET generation = generation + 1", [])
        .expect("advance graph snapshot");
    let stale = published_public_kast(&home, &config_home, &workspace)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "graph",
            "nodes",
            "--continuation",
            first_continuation
                .as_deref()
                .expect("snapshot-bound continuation"),
        ])
        .output()
        .expect("stale graph continuation");
    assert_eq!(stale.status.code(), Some(1), "{stale:?}");
    let stale: serde_json::Value =
        serde_json::from_slice(&stale.stdout).expect("stale graph rejection JSON");
    assert_eq!(
        stale["result"]["failure"]["code"], "GRAPH_PAGE_EXPIRED",
        "{stale:#}"
    );
}

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
