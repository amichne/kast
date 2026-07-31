use super::*;

impl ReferenceFixture {
    fn install_repository_base(&self) -> PathBuf {
        let database = self.database();
        let base = database.with_file_name("repository-base.db");
        self.index
            .connection()
            .execute(
                "INSERT INTO symbol_references VALUES
                 (1, 'Source0001.kt', 30, 3, 2, 1, 'Source0000.kt', 1, 'CALL')",
                [],
            )
            .expect("base reference to changed target");
        std::fs::copy(&database, &base).expect("repository base database");
        self.index
            .connection()
            .execute_batch(
                "CREATE TABLE IF NOT EXISTS repository_overlay_tombstones(
                     path TEXT PRIMARY KEY
                 ) WITHOUT ROWID;",
            )
            .expect("repository overlay tables");
        std::fs::write(
            database.with_file_name("repository-overlay.json"),
            serde_json::to_vec(&serde_json::json!({ "baseDatabase": base }))
                .expect("repository overlay descriptor"),
        )
        .expect("repository overlay descriptor");
        database
    }

    fn install_partial_repository_overlay(&self) {
        self.install_repository_base();
        self.index
            .connection()
            .execute_batch(
                "DELETE FROM file_stage_outcomes
                 WHERE filename IN ('Source0001.kt', 'Source0002.kt');
                 DELETE FROM file_manifest
                 WHERE filename IN ('Source0001.kt', 'Source0002.kt');
                 DELETE FROM declarations
                 WHERE filename IN ('Source0001.kt', 'Source0002.kt');
                 DELETE FROM symbol_references
                 WHERE src_filename IN ('Source0001.kt', 'Source0002.kt')
                    OR (src_filename = 'Source0000.kt' AND source_offset = 20);",
            )
            .expect("partial repository overlay");
    }

    fn install_materialized_repository_overlay(&self) {
        self.install_repository_base();
        self.index
            .connection()
            .execute(
                "DELETE FROM symbol_references
                 WHERE src_filename = 'Source0001.kt' AND source_offset = 30",
                [],
            )
            .expect("materialized target invalidation");
    }
}

#[test]
fn derive_qualifies_unattributed_reference_sources() {
    let fixture = ReferenceFixture::new();
    fixture
        .index
        .connection()
        .execute(
            "INSERT INTO symbol_references VALUES
             (1, 'Source0000.kt', 30, NULL, 5, NULL, NULL, NULL, 'CALL')",
            [],
        )
        .expect("unattributed reference source");

    assert_success(&fixture.derive("topology.json", None));
    let artifact: serde_json::Value = serde_json::from_slice(&fixture.artifact("topology.json"))
        .expect("qualified artifact JSON");

    assert_eq!(artifact["source"]["qualification"], "QUALIFIED");
    assert_eq!(artifact["source"]["coverage"]["unattributedSourceEdges"], 1);
    assert!(
        artifact["source"]["coverage"]["limitations"]
            .as_array()
            .is_some_and(|limitations| {
                limitations
                    .iter()
                    .any(|limitation| limitation == "UNATTRIBUTED_REFERENCE_SOURCE")
            }),
        "{artifact:#}"
    );
}

#[test]
fn derive_rejects_unattributed_pending_kotlin_updates() {
    let fixture = ReferenceFixture::new();
    fixture
        .index
        .connection()
        .execute(
            "INSERT INTO pending_updates(op, prefix_id, filename, epoch_ms, applied)
             VALUES ('upsert_file', 9, 'Pending.kt', 1, 0)",
            [],
        )
        .expect("unknown pending update");

    let output = fixture.derive("topology.json", None);

    assert_eq!(output.status.code(), Some(1), "{output:?}");
    assert!(!fixture.workspace.join("topology.json").exists());
    assert!(
        String::from_utf8_lossy(&output.stdout).contains("DERIVED_TOPOLOGY_REFERENCE_INCOMPLETE"),
        "{output:?}"
    );
}

#[test]
fn derive_qualifies_fully_accounted_limited_coverage() {
    let fixture = ReferenceFixture::new();
    fixture
        .index
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET outcome_status = 'LIMITED', limitations_json = '[\"FIXTURE_LIMIT\"]'
             WHERE stage = 'RELATIONSHIPS'",
            [],
        )
        .expect("limited relationship coverage");

    assert_success(&fixture.derive("topology.json", None));
    let artifact: serde_json::Value = serde_json::from_slice(&fixture.artifact("topology.json"))
        .expect("qualified artifact JSON");

    assert_eq!(artifact["source"]["qualification"], "QUALIFIED");
    assert_eq!(artifact["source"]["coverage"]["complete"], 0);
    assert_eq!(artifact["source"]["coverage"]["limited"], 3);
}

#[test]
fn derive_rejects_references_without_a_source_manifest() {
    let fixture = ReferenceFixture::new();
    fixture
        .index
        .connection()
        .execute(
            "INSERT INTO symbol_references VALUES
             (1, 'Orphan.kt', 1, 2, 3, 1, 'Source0001.kt', 1, 'CALL')",
            [],
        )
        .expect("orphan reference");

    let output = fixture.derive("topology.json", None);

    assert_eq!(output.status.code(), Some(1), "{output:?}");
    assert!(!fixture.workspace.join("topology.json").exists());
    assert!(
        String::from_utf8_lossy(&output.stdout).contains("DERIVED_TOPOLOGY_REFERENCE_INCOMPLETE"),
        "{output:?}"
    );
}

#[test]
fn derive_composes_repository_base_and_overlay_reference_facts() {
    let fixture = ReferenceFixture::new();
    fixture.install_partial_repository_overlay();

    assert_success(&fixture.derive("topology.json", None));
    let artifact: serde_json::Value = serde_json::from_slice(&fixture.artifact("topology.json"))
        .expect("repository overlay artifact JSON");

    assert_eq!(artifact["source"]["qualification"], "QUALIFIED");
    assert_eq!(artifact["source"]["coverage"]["complete"], 3);
    assert_eq!(artifact["source"]["coverage"]["invalidatedTargetEdges"], 1);
    assert_eq!(artifact["nodes"].as_array().map(Vec::len), Some(4));
    assert!(artifact["edges"].as_array().is_some_and(|edges| {
        edges.iter().any(|edge| {
            edge["source"] == "sample.PaymentController"
                && edge["target"] == "sample.PaymentService"
                && edge["occurrenceCount"] == 1
        }) && edges.iter().any(|edge| edge["kind"] == "TYPE_REF")
            && edges.iter().any(|edge| edge["kind"] == "ANNOTATION")
            && !edges.iter().any(|edge| {
                edge["source"] == "sample.PaymentService"
                    && edge["target"] == "sample.PaymentController"
            })
    }));
}

#[test]
fn derive_excludes_normal_path_repository_tombstones() {
    let fixture = ReferenceFixture::new();
    fixture
        .index
        .connection()
        .execute(
            "UPDATE path_prefixes SET dir_path = 'src/main/kotlin/sample' WHERE prefix_id = 1",
            [],
        )
        .expect("production relative path codec");
    fixture.install_partial_repository_overlay();
    fixture
        .index
        .connection()
        .execute(
            "INSERT INTO repository_overlay_tombstones(path)
             VALUES ('src/main/kotlin/sample/Source0002.kt')",
            [],
        )
        .expect("repository deletion tombstone");

    assert_success(&fixture.derive("topology.json", None));
    let artifact: serde_json::Value = serde_json::from_slice(&fixture.artifact("topology.json"))
        .expect("repository tombstone artifact JSON");

    assert_eq!(artifact["source"]["qualification"], "QUALIFIED");
    assert_eq!(artifact["source"]["coverage"]["total"], 2);
    assert_eq!(artifact["source"]["coverage"]["invalidatedTargetEdges"], 2);
    assert!(
        artifact["nodes"].as_array().is_some_and(|nodes| {
            nodes.len() == 3
                && nodes
                    .iter()
                    .all(|node| node["key"] != "sample.PaymentRepository")
        }),
        "{artifact:#}"
    );
}

#[test]
fn derive_qualifies_materialized_overlay_reference_invalidation() {
    let fixture = ReferenceFixture::new();
    fixture.install_materialized_repository_overlay();

    assert_success(&fixture.derive("topology.json", None));
    let artifact: serde_json::Value = serde_json::from_slice(&fixture.artifact("topology.json"))
        .expect("materialized overlay artifact JSON");

    assert_eq!(artifact["source"]["qualification"], "QUALIFIED");
    assert!(
        artifact["source"]["coverage"]["limitations"]
            .as_array()
            .is_some_and(|limitations| {
                limitations
                    .iter()
                    .any(|value| value == "REPOSITORY_OVERLAY_REFERENCE_COMPOSITION")
            }),
        "{artifact:#}"
    );
    assert!(artifact["edges"].as_array().is_some_and(|edges| {
        !edges.iter().any(|edge| {
            edge["source"] == "sample.PaymentService"
                && edge["target"] == "sample.PaymentController"
        })
    }));
}
