#[path = "../../support/mod.rs"]
mod support;

use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

use support::workspace_database_path_for_test;
use support::workspace_files::WorkspaceIndexFixture;

struct ReferenceFixture {
    _temp: tempfile::TempDir,
    workspace: PathBuf,
    index: WorkspaceIndexFixture,
}

impl ReferenceFixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().expect("temporary derived-topology fixture");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let database = workspace_database_path_for_test(&workspace);
        let index = WorkspaceIndexFixture::at_database_path(&workspace, &database);
        index.seed_high_cardinality_sources(3);
        index
            .connection()
            .execute_batch(
                "UPDATE path_prefixes
                     SET dir_path = '__kast_rel__/src/main/kotlin/sample'
                     WHERE prefix_id = 1;
                 CREATE TABLE declarations(
                     fq_id INTEGER NOT NULL,
                     kind TEXT NOT NULL,
                     visibility TEXT NOT NULL,
                     prefix_id INTEGER NOT NULL,
                     filename TEXT NOT NULL,
                     declaration_offset INTEGER,
                     module_path TEXT,
                     source_set TEXT,
                     PRIMARY KEY(fq_id, prefix_id, filename)
                 );
                 CREATE TABLE symbol_references(
                     src_prefix_id INTEGER NOT NULL,
                     src_filename TEXT NOT NULL,
                     source_offset INTEGER NOT NULL,
                     source_fq_id INTEGER,
                     target_fq_id INTEGER NOT NULL,
                     tgt_prefix_id INTEGER,
                     tgt_filename TEXT,
                     target_offset INTEGER,
                     edge_kind TEXT NOT NULL,
                     PRIMARY KEY(src_prefix_id, src_filename, source_offset, target_fq_id)
                 );
                 INSERT INTO fq_names(fq_id, fq_name) VALUES
                     (2, 'sample.PaymentController'),
                     (3, 'sample.PaymentService'),
                     (4, 'sample.PaymentRepository'),
                     (5, 'external.AuditLogger');
                 INSERT INTO declarations VALUES
                     (2, 'CLASS', 'PUBLIC', 1, 'Source0000.kt', 1, ':app', 'main'),
                     (3, 'CLASS', 'PUBLIC', 1, 'Source0001.kt', 1, ':app', 'main'),
                     (4, 'CLASS', 'PUBLIC', 1, 'Source0002.kt', 1, ':app', 'main');
                 INSERT INTO symbol_references VALUES
                     (1, 'Source0000.kt', 10, 2, 3, 1, 'Source0001.kt', 1, 'CALL'),
                     (1, 'Source0000.kt', 20, 2, 3, 1, 'Source0001.kt', 1, 'CALL'),
                     (1, 'Source0001.kt', 10, 3, 4, 1, 'Source0002.kt', 1, 'TYPE_REF'),
                     (1, 'Source0002.kt', 10, 4, 3, 1, 'Source0001.kt', 1, 'CALL'),
                     (1, 'Source0001.kt', 20, 3, 5, NULL, NULL, NULL, 'ANNOTATION');",
            )
            .expect("reference graph fixture");
        Self {
            _temp: temp,
            workspace,
            index,
        }
    }

    fn run(&self, arguments: &[&str]) -> Output {
        let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
        let home = self
            .workspace
            .parent()
            .expect("fixture parent")
            .join("home");
        command.arg0("kast");
        command
            .current_dir(&self.workspace)
            .env("HOME", &home)
            .env("KAST_HOME", home.join(".local/share/kast"))
            .env("KAST_CONFIG_HOME", self._temp.path().join("config"))
            .args(arguments)
            .output()
            .expect("run derived topology command")
    }

    fn derive(&self, output: &str, prior: Option<&str>) -> Output {
        let mut arguments = vec![
            "graph",
            "derive",
            "--experimental-derived-topology",
            "--out",
            output,
        ];
        if let Some(prior) = prior {
            arguments.extend(["--prior", prior]);
        }
        self.run(&arguments)
    }

    fn artifact(&self, relative: &str) -> Vec<u8> {
        std::fs::read(self.workspace.join(relative)).expect("derived topology artifact")
    }

    fn database(&self) -> PathBuf {
        self.index.database_path().to_path_buf()
    }
}

fn assert_success(output: &Output) {
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn derive_requires_the_explicit_experimental_gate_before_writing() {
    let fixture = ReferenceFixture::new();
    let output = fixture.run(&["graph", "derive", "--out", "topology.json"]);

    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(!fixture.workspace.join("topology.json").exists());
    assert!(
        String::from_utf8_lossy(&output.stdout).contains("--experimental-derived-topology"),
        "{output:?}"
    );
}

#[test]
fn derive_writes_a_deterministic_qualified_reference_artifact() {
    let fixture = ReferenceFixture::new();
    let first = fixture.derive("topology-a.json", None);
    let second = fixture.derive("topology-b.json", None);
    assert_success(&first);
    assert_success(&second);

    let first = fixture.artifact("topology-a.json");
    let second = fixture.artifact("topology-b.json");
    assert_eq!(first, second, "same input must produce identical bytes");
    let artifact: serde_json::Value = serde_json::from_slice(&first).expect("artifact JSON");
    assert_eq!(artifact["type"], "KAST_DERIVED_TOPOLOGY");
    assert_eq!(artifact["schemaVersion"], 1);
    assert_eq!(artifact["evidenceClass"], "STATISTICAL_DERIVATION");
    assert_eq!(artifact["source"]["lane"], "REFERENCE_DERIVED");
    assert_eq!(artifact["source"]["qualification"], "CURRENT");
    assert_eq!(artifact["source"]["generation"], 41);
    assert_eq!(artifact["source"]["coverage"]["complete"], 3);
    let digest = artifact["source"]["inputDigest"]
        .as_str()
        .expect("input digest");
    assert!(digest.len() == 64 && digest.bytes().all(|byte| byte.is_ascii_hexdigit()));
    assert_eq!(
        artifact["algorithm"]["name"],
        "KAST_DETERMINISTIC_PARTITION_V1"
    );
    assert_eq!(artifact["algorithm"]["version"], 1);
    assert_eq!(artifact["algorithm"]["resolution"], 1.0);
    assert_eq!(artifact["algorithm"]["weighting"], "LOG1P_OCCURRENCE_COUNT");
    assert_eq!(artifact["nodes"].as_array().map(Vec::len), Some(4));
    assert_eq!(
        artifact["nodes"]
            .as_array()
            .and_then(|nodes| {
                nodes
                    .iter()
                    .find(|node| node["key"] == "sample.PaymentController")
            })
            .and_then(|node| node["path"].as_str()),
        Some("src/main/kotlin/sample/Source0000.kt")
    );
    assert!(
        artifact["nodes"]
            .as_array()
            .is_some_and(|nodes| nodes.iter().all(|node| {
                node["community"].is_number()
                    && node["roles"]
                        .as_array()
                        .is_some_and(|roles| !roles.is_empty())
                    && node["retrievalTerms"]
                        .as_array()
                        .is_some_and(|terms| !terms.is_empty())
            })),
        "{artifact:#}"
    );
    assert!(
        artifact["edges"]
            .as_array()
            .is_some_and(|edges| edges.iter().any(|edge| edge["kind"] == "CALL"
                && edge["relationshipClass"] == "RUNTIME"
                && edge["occurrenceCount"] == 2
                && edge["normalizedWeight"]
                    .as_f64()
                    .is_some_and(|weight| (weight - 2.0_f64.ln_1p()).abs() < f64::EPSILON))),
        "{artifact:#}"
    );
    let edges = artifact["edges"].as_array().expect("typed edges");
    for (kind, class) in [
        ("CALL", "RUNTIME"),
        ("TYPE_REF", "TYPE_DEPENDENCY"),
        ("ANNOTATION", "METADATA"),
    ] {
        assert!(
            edges
                .iter()
                .any(|edge| edge["kind"] == kind && edge["relationshipClass"] == class)
        );
    }
    assert!(
        artifact["communities"]
            .as_array()
            .is_some_and(|communities| !communities.is_empty()
                && communities.iter().all(|community| {
                    community["label"]
                        .as_str()
                        .is_some_and(|label| !label.is_empty())
                        && community["members"].as_array().is_some_and(|members| {
                            community["memberCount"].as_u64() == u64::try_from(members.len()).ok()
                                && community["representativeSymbols"].as_array().is_some_and(
                                    |representatives| {
                                        representatives
                                            .iter()
                                            .all(|representative| members.contains(representative))
                                    },
                                )
                        })
                        && community["cohesion"]
                            .as_f64()
                            .is_some_and(|value| (0.0..=1.0).contains(&value))
                        && community["conductance"]
                            .as_f64()
                            .is_some_and(|value| (0.0..=1.0).contains(&value))
                        && community["representativeSymbols"]
                            .as_array()
                            .is_some_and(|symbols| !symbols.is_empty())
                })),
        "{artifact:#}"
    );
    let nodes = artifact["nodes"].as_array().expect("derived nodes");
    let communities = artifact["communities"].as_array().expect("communities");
    assert_eq!(
        communities
            .iter()
            .filter_map(|community| community["members"].as_array().map(Vec::len))
            .sum::<usize>(),
        nodes.len()
    );
    assert!(nodes.iter().all(|node| communities.iter().any(|community| {
        community["id"] == node["community"]
            && community["members"]
                .as_array()
                .is_some_and(|members| members.contains(&node["key"]))
            && community["labelTerms"].as_array().is_some_and(|labels| {
                node["retrievalTerms"]
                    .as_array()
                    .is_some_and(|terms| labels.iter().all(|label| terms.contains(label)))
            })
    })));
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
fn derive_reports_prior_generation_lineage_and_topology_change() {
    let fixture = ReferenceFixture::new();
    assert_success(&fixture.derive("prior.json", None));
    let prior: serde_json::Value =
        serde_json::from_slice(&fixture.artifact("prior.json")).expect("prior artifact JSON");
    let same_generation = fixture.derive("same-generation.json", Some("prior.json"));
    assert_eq!(
        same_generation.status.code(),
        Some(1),
        "{same_generation:?}"
    );
    assert!(!fixture.workspace.join("same-generation.json").exists());
    let connection = rusqlite::Connection::open(fixture.database()).expect("fixture database");
    connection
        .execute_batch(
            "UPDATE schema_version SET generation = 42;
             INSERT INTO fq_names(fq_id, fq_name) VALUES (6, 'sample.NewWorker');
             INSERT INTO declarations VALUES
                 (6, 'CLASS', 'PUBLIC', 1, 'Source0002.kt', 30, ':app', 'main');",
        )
        .expect("next reference generation");
    drop(connection);

    let output = fixture.derive("current.json", Some("prior.json"));
    assert_success(&output);
    let artifact: serde_json::Value =
        serde_json::from_slice(&fixture.artifact("current.json")).expect("lineage artifact JSON");
    assert_eq!(artifact["source"]["generation"], 42);
    assert_eq!(artifact["lineage"]["previousGeneration"], 41);
    assert_eq!(
        artifact["lineage"]["previousInputDigest"],
        prior["source"]["inputDigest"]
    );
    assert_ne!(
        artifact["source"]["inputDigest"],
        prior["source"]["inputDigest"]
    );
    let worker_community = artifact["nodes"]
        .as_array()
        .and_then(|nodes| nodes.iter().find(|node| node["key"] == "sample.NewWorker"))
        .and_then(|node| node["community"].as_u64())
        .expect("new worker community");
    assert!(
        artifact["lineage"]["communities"]
            .as_array()
            .is_some_and(|entries| entries.iter().any(|entry| {
                entry["community"] == worker_community && entry["status"] == "NEW"
            })),
        "{artifact:#}"
    );
    assert!(
        artifact["changes"]["addedNodes"]
            .as_array()
            .is_some_and(|nodes| nodes.iter().any(|node| node == "sample.NewWorker")),
        "{artifact:#}"
    );
}

#[test]
fn derive_rejects_an_output_path_outside_the_workspace() {
    let fixture = ReferenceFixture::new();
    let output = fixture.derive("../escape.json", None);

    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(
        !Path::new(fixture.workspace.parent().expect("fixture parent"))
            .join("escape.json")
            .exists()
    );
}
