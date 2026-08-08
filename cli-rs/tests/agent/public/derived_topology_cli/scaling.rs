use super::*;

const LARGE_FIXTURE_COMMUNITY_COUNT: usize = 96;
const WORK_EVIDENCE_PREFIX: &str = "KAST_TEST_DERIVED_TOPOLOGY_WORK_EVIDENCE=";

impl ReferenceFixture {
    fn replace_with_disconnected_pairs(&self, community_count: usize) {
        let node_count = community_count.checked_mul(2).expect("fixture node count");
        self.index
            .connection()
            .execute_batch(
                "DELETE FROM symbol_references;
                 DELETE FROM declarations;
                 DELETE FROM file_gradle_source_sets;
                 DELETE FROM file_gradle_projects;
                 DELETE FROM file_metadata;
                 DELETE FROM file_stage_outcomes;
                 DELETE FROM file_manifest;
                 DELETE FROM fq_names WHERE fq_id >= 2;",
            )
            .expect("clear small reference graph fixture");
        self.index.seed_high_cardinality_sources(node_count);

        let mut connection = self.index.connection();
        let transaction = connection
            .transaction()
            .expect("large reference graph transaction");
        for index in 0..node_count {
            let fq_id = i64::try_from(index + 2).expect("fixture fq id");
            let fq_name = format!("large.Component{index:04}");
            let filename = format!("Source{index:04}.kt");
            transaction
                .execute(
                    "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
                    rusqlite::params![fq_id, fq_name],
                )
                .expect("large fixture fq name");
            transaction
                .execute(
                    "INSERT INTO declarations VALUES (?, 'CLASS', 'PUBLIC', 1, ?, 1, ':app', 'main')",
                    rusqlite::params![fq_id, filename],
                )
                .expect("large fixture declaration");
        }
        for community in 0..community_count {
            let source_index = community * 2;
            let source_fq_id = i64::try_from(source_index + 2).expect("source fq id");
            let target_fq_id = source_fq_id + 1;
            let source_filename = format!("Source{source_index:04}.kt");
            let target_filename = format!("Source{:04}.kt", source_index + 1);
            transaction
                .execute(
                    "INSERT INTO symbol_references VALUES (1, ?, 10, ?, ?, 1, ?, 1, 'CALL')",
                    rusqlite::params![source_filename, source_fq_id, target_fq_id, target_filename],
                )
                .expect("large fixture reference edge");
        }
        transaction.commit().expect("large reference graph commit");
    }

    fn derive_with_work_evidence(&self, output: &str) -> Output {
        let arguments = [
            "graph",
            "derive",
            "--experimental-derived-topology",
            "--out",
            output,
        ];
        let home = self
            .workspace
            .parent()
            .expect("fixture parent")
            .join("home");
        let config_home = self._temp.path().join("config");
        let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
        command
            .arg0("kast")
            .current_dir(&self.workspace)
            .env("HOME", &home)
            .env("KAST_HOME", home.join(".local/share/kast"))
            .env("KAST_CONFIG_HOME", &config_home)
            .env("KAST_TEST_DERIVED_TOPOLOGY_WORK_EVIDENCE", "1");
        published_semantic_command_for_reads(command, &home, &config_home, &self.workspace, 1)
            .args(arguments)
            .output()
            .expect("run instrumented derived topology command")
    }
}

#[test]
fn derive_visits_each_edge_once_while_preserving_deterministic_output() {
    let fixture = ReferenceFixture::new();
    fixture.replace_with_disconnected_pairs(LARGE_FIXTURE_COMMUNITY_COUNT);

    let minimally_instrumented = fixture.derive("minimal.json", None);
    let instrumented = fixture.derive_with_work_evidence("instrumented.json");
    assert_success(&minimally_instrumented);
    assert_success(&instrumented);
    assert_eq!(
        fixture.artifact("minimal.json"),
        fixture.artifact("instrumented.json"),
        "work instrumentation must preserve the golden derived topology bytes"
    );

    let stderr = String::from_utf8(instrumented.stderr).expect("UTF-8 work evidence");
    let evidence = stderr
        .lines()
        .find_map(|line| line.strip_prefix(WORK_EVIDENCE_PREFIX))
        .map(|value| serde_json::from_str::<serde_json::Value>(value).expect("work evidence JSON"))
        .expect("derived topology work evidence");
    let edge_count = evidence["edgeCount"].as_u64().expect("edge count");
    let community_count = evidence["communityCount"]
        .as_u64()
        .expect("community count");
    let edge_visits = evidence["edgeVisits"].as_u64().expect("edge visits");

    assert_eq!(edge_count, LARGE_FIXTURE_COMMUNITY_COUNT as u64);
    assert_eq!(community_count, LARGE_FIXTURE_COMMUNITY_COUNT as u64);
    assert!(
        edge_visits <= edge_count,
        "projection visited {edge_visits} edges for {edge_count} edges and {community_count} communities; work must be bounded by graph edges"
    );
}
