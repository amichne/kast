    use super::*;
    use crate::metrics::MetricsRequest;
    use rusqlite::{Connection, params};
    use serde_json::Value;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Barrier};
    use std::thread;

    struct Fixture {
        _temp: tempfile::TempDir,
        workspace: PathBuf,
        database: PathBuf,
    }

    impl Fixture {
        fn request(
            &self,
            metric: &'static str,
            symbol: Option<&str>,
            limit: usize,
            depth: usize,
        ) -> MetricsRequest {
            MetricsRequest::for_test(
                self.workspace.clone(),
                self.database.clone(),
                metric,
                symbol.map(str::to_string),
                limit,
                depth,
            )
            .expect("test metrics request")
        }
    }

    #[test]
    fn search_uses_exact_match_then_persistent_trigram_fts() {
        let fixture = seed_fixture();
        let request = fixture.request("search", Some("Foo"), 10, 1);
        let db = MetricsDatabase::open(&request).expect("open metrics db");

        let before = db.conn.total_changes();
        let exact = strings(db.search("lib.Foo", 10).expect("exact search"));
        let after_first = db.conn.total_changes();
        let substring = strings(db.search("Widget", 10).expect("substring search"));
        let after_second = db.conn.total_changes();
        let short = strings(db.search("Fo", 10).expect("short search"));
        let after_short = db.conn.total_changes();

        assert_eq!(exact.first().map(String::as_str), Some("lib.Foo"));
        assert!(
            exact.iter().any(|item| item == "lib.FooWidget"),
            "persistent FTS should provide broader ranked results after the exact match: {exact:?}"
        );
        assert!(
            substring.iter().any(|item| item == "lib.FooWidget"),
            "substring search should use persistent trigram FTS: {substring:?}"
        );
        assert!(
            short.iter().any(|item| item == "lib.FooWidget"),
            "short search should use direct prefix fallback before trigram FTS: {short:?}"
        );
        assert_eq!(
            before, after_first,
            "search must not create temp FTS tables"
        );
        assert_eq!(
            after_first, after_second,
            "subsequent search must keep the read-only connection unchanged"
        );
        assert_eq!(
            after_second, after_short,
            "short search must keep the read-only connection unchanged"
        );
    }

    #[test]
    fn impact_returns_typed_total_and_truncation_with_bounded_rows() {
        let fixture = seed_fixture();
        seed_high_cardinality_impact(&fixture, 500);
        let request = fixture.request("impact", Some("lib.Popular"), 1, 3);
        let db = MetricsDatabase::open(&request).expect("open metrics db");

        let result = db.impact("lib.Popular", 3, 1).expect("bounded impact");

        assert_eq!(result.total_count, 503);
        assert_eq!(result.returned_count, 1);
        assert!(result.truncated);
        assert_eq!(result.results.as_array().expect("impact results").len(), 1);
    }

    #[test]
    fn anchored_impact_pages_503_nodes_without_overlap() {
        let fixture = seed_fixture();
        seed_high_cardinality_impact(&fixture, 500);
        let request = fixture.request("impact", Some("lib.Popular"), 4, 3);
        let db = MetricsDatabase::open(&request).expect("open metrics db");
        let subject = ImpactSubjectIdentity::new(
            "lib.Popular".to_string(),
            fixture.workspace.join("lib/Popular.kt"),
            1,
            ImpactSubjectKind::Class,
        );

        let first = db
            .impact_page(&subject, 3, 4, AgentImpactPageOffset::first())
            .expect("first impact page");
        let second = db
            .impact_page(
                &subject,
                3,
                4,
                first.next_offset.expect("first continuation offset"),
            )
            .expect("second impact page");
        let first_paths = first
            .results
            .as_array()
            .expect("first nodes")
            .iter()
            .map(|node| node["sourcePath"].as_str().expect("first path"))
            .collect::<std::collections::BTreeSet<_>>();
        let second_paths = second
            .results
            .as_array()
            .expect("second nodes")
            .iter()
            .map(|node| node["sourcePath"].as_str().expect("second path"))
            .collect::<std::collections::BTreeSet<_>>();

        assert_eq!(first.total_count, 503);
        assert_eq!(second.total_count, 503);
        assert_eq!(first.returned_count, 4);
        assert_eq!(second.returned_count, 4);
        assert!(first.truncated);
        assert!(second.truncated);
        assert!(first_paths.is_disjoint(&second_paths));
    }

    #[test]
    fn anchored_impact_rejects_unprovable_index_identity_before_impact_rows() {
        let fixture = seed_fixture();
        let request = fixture.request("impact", Some("lib.Popular"), 4, 3);
        let db = MetricsDatabase::open(&request).expect("open metrics db");
        let missing = ImpactSubjectIdentity::new(
            "lib.Popular".to_string(),
            fixture.workspace.join("lib/Popular.kt"),
            99,
            ImpactSubjectKind::Class,
        );

        let error = db
            .impact_page(&missing, 3, 4, AgentImpactPageOffset::first())
            .expect_err("mismatched declaration offset")
            .into_cli_error();

        assert_eq!(error.code, "IMPACT_INDEX_IDENTITY_UNAVAILABLE");
    }

    #[test]
    fn anchored_callable_impact_degrades_after_exact_index_identity() {
        let fixture = seed_fixture();
        Connection::open(&fixture.database)
            .expect("sqlite")
            .execute(
                "UPDATE declarations SET kind = 'FUNCTION' WHERE fq_id = 7",
                [],
            )
            .expect("callable declaration");
        let request = fixture.request("impact", Some("lib.Popular"), 4, 3);
        let db = MetricsDatabase::open(&request).expect("open metrics db");
        let subject = ImpactSubjectIdentity::new(
            "lib.Popular".to_string(),
            fixture.workspace.join("lib/Popular.kt"),
            1,
            ImpactSubjectKind::Function,
        );

        let error = db
            .impact_page(&subject, 3, 4, AgentImpactPageOffset::first())
            .expect_err("callable impact must degrade")
            .into_cli_error();

        assert_eq!(error.code, "IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE");
    }

    #[test]
    fn impact_count_and_nodes_share_snapshot_during_concurrent_commit() {
        let fixture = seed_fixture();
        let journal = Connection::open(&fixture.database)
            .expect("sqlite")
            .query_row("PRAGMA journal_mode=WAL", [], |row| row.get::<_, String>(0))
            .expect("enable WAL");
        assert_eq!(journal, "wal");

        let request = fixture.request("impact", Some("lib.Popular"), 10, 3);
        let count_complete = Arc::new(Barrier::new(2));
        let mutation_complete = Arc::new(Barrier::new(2));
        let mut db = MetricsDatabase::open(&request).expect("open metrics db");
        db.impact_snapshot_barrier = Some(ImpactSnapshotBarrier {
            count_complete: Arc::clone(&count_complete),
            mutation_complete: Arc::clone(&mutation_complete),
        });

        let database = fixture.database.clone();
        let writer = thread::spawn(move || {
            count_complete.wait();
            let result = seed_impact_sources(&database, 10_000, 4, 40);
            mutation_complete.wait();
            result
        });

        let result = db
            .impact("lib.Popular", 3, 10)
            .expect("snapshot impact");
        writer
            .join()
            .expect("impact writer thread")
            .expect("concurrent impact commit");
        let nodes = result.results.as_array().expect("impact results");
        let source_paths = nodes
            .iter()
            .map(|node| {
                let source_path = node["sourcePath"].as_str().expect("impact source path");
                Path::new(source_path)
                    .strip_prefix(&fixture.workspace)
                    .expect("workspace-relative impact path")
                    .to_str()
                    .expect("UTF-8 impact path")
            })
            .collect::<Vec<_>>();

        assert_eq!(
            (
                result.total_count,
                result.returned_count,
                nodes.len(),
                result.returned_count <= result.total_count,
                source_paths,
            ),
            (
                3,
                3,
                3,
                true,
                vec!["app/A.kt", "app/B.kt", "app/C.kt"],
            ),
        );
    }

    fn seed_high_cardinality_impact(fixture: &Fixture, source_count: usize) {
        seed_impact_sources(&fixture.database, 1_000, source_count, 1)
            .expect("seed high-cardinality impact");
    }

    fn seed_impact_sources(
        database: &Path,
        first_fq_id: i64,
        source_count: usize,
        references_per_source: usize,
    ) -> rusqlite::Result<()> {
        let mut conn = Connection::open(database)?;
        let tx = conn.transaction()?;
        for index in 0..source_count {
            let fq_id = first_fq_id + i64::try_from(index).expect("impact fq id");
            let fq_name = format!("app.ImpactSource{fq_id:04}");
            let filename = format!("ImpactSource{fq_id:04}.kt");
            tx.execute(
                "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
                params![fq_id, fq_name],
            )?;
            tx.execute(
                "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (1, ?, ':app', 'main')",
                params![filename],
            )?;
            tx.execute(
                "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (1, ?, 1)",
                params![filename],
            )?;
            tx.execute(
                "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, 'CLASS', 'PUBLIC', 1, ?, 1, ':app', 'main')",
                params![fq_id, filename],
            )?;
            for source_offset in 1..=references_per_source {
                let source_offset = i64::try_from(source_offset).expect("impact source offset");
                tx.execute(
                    "INSERT INTO symbol_references(src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, tgt_prefix_id, tgt_filename, target_offset, edge_kind) VALUES (1, ?, ?, ?, 7, 2, 'Popular.kt', 1, 'CALL')",
                    params![filename, source_offset, fq_id],
                )?;
            }
        }
        tx.commit()
    }
