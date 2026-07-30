#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn declaration_term_prefers_the_specific_camel_case_name() {
        assert_eq!(
            likely_declaration_term(
                "Does backend main contain DefinitelyMissingBackendSymbol in Kotlin?"
            ),
            Some("DefinitelyMissingBackendSymbol")
        );
    }

    #[test]
    fn coverage_counts_every_closed_state_once() {
        let counts = count_states(
            [
                GraphFileState::Indexed,
                GraphFileState::Excluded,
                GraphFileState::Pending,
                GraphFileState::Limited,
                GraphFileState::Failed,
                GraphFileState::Stale,
            ]
            .into_iter(),
        );
        assert_eq!(counts.total, 6);
        assert_eq!(counts.indexed, 1);
        assert_eq!(counts.excluded, 1);
        assert_eq!(counts.pending, 1);
        assert_eq!(counts.limited, 1);
        assert_eq!(counts.failed, 1);
        assert_eq!(counts.stale, 1);
    }

    #[test]
    fn gradle_generated_sources_are_explicitly_excluded() {
        assert!(is_generated_source(Path::new(
            "build-logic/build/generated-sources/kotlin-dsl-accessors/Accessor.kt"
        )));
        assert!(!is_generated_source(Path::new(
            "build-logic/src/main/kotlin/Plugin.kt"
        )));
    }

    #[test]
    fn repository_generation_is_pinned_for_one_sqlite_read_epoch() {
        let temp = tempfile::tempdir().expect("temporary repository database");
        let database = temp.path().join("source-index.db");
        let writer = Connection::open(&database).expect("writer");
        writer
            .execute_batch(
                "PRAGMA journal_mode=WAL;
                 CREATE TABLE schema_version (generation INTEGER NOT NULL);
                 INSERT INTO schema_version(generation) VALUES (41);",
            )
            .expect("generation schema");
        let mut reader = Connection::open(&database).expect("reader");
        let transaction = reader
            .transaction_with_behavior(TransactionBehavior::Deferred)
            .expect("read transaction");

        assert_eq!(repository_generation(&transaction).expect("generation"), 41);
        writer
            .execute("UPDATE schema_version SET generation = 42", [])
            .expect("concurrent generation movement");
        assert_eq!(
            repository_generation(&transaction).expect("pinned generation"),
            41
        );

        transaction.commit().expect("read transaction commit");
        assert_eq!(repository_generation(&reader).expect("new generation"), 42);
    }

    #[test]
    fn graph_refresh_plan_rebinds_scope_without_selecting_external_boundaries() {
        let indexed = graph_file("src/Indexed.kt", GraphFileState::Indexed, None);
        let pending = graph_file(
            "src/Pending.kt",
            GraphFileState::Pending,
            Some("SEMANTIC_GRAPH_MISSING"),
        );
        let (selected, removed) = plan_semantic_graph_refresh_files(
            &[indexed.clone(), pending],
            &BTreeSet::from(["src/Indexed.kt".to_string()]),
            &[],
        );
        assert_eq!(
            selected,
            ["src/Indexed.kt".to_string(), "src/Pending.kt".to_string()]
        );
        assert!(removed.is_empty());

        let external = graph_file(
            "src/External.kt",
            GraphFileState::Limited,
            Some("SEMANTIC_GRAPH_EXTERNAL_BOUNDARY"),
        );
        let (selected, removed) = plan_semantic_graph_refresh_files(
            &[external, indexed.clone()],
            &BTreeSet::from([
                "src/External.kt".to_string(),
                "src/Indexed.kt".to_string(),
            ]),
            &[],
        );
        assert_eq!(selected, ["src/Indexed.kt".to_string()]);
        assert_eq!(removed, ["src/External.kt".to_string()]);

        let (selected, removed) = plan_semantic_graph_refresh_files(
            &[indexed],
            &BTreeSet::from([
                "src/Gone.kt".to_string(),
                "src/Indexed.kt".to_string(),
            ]),
            &["src/Gone.kt".to_string()],
        );
        assert_eq!(selected, ["src/Indexed.kt".to_string()]);
        assert_eq!(removed, ["src/Gone.kt".to_string()]);
    }

    fn graph_file(
        path: &str,
        state: GraphFileState,
        reason_code: Option<&'static str>,
    ) -> GraphFileCoverage {
        GraphFileCoverage {
            path: path.to_string(),
            state,
            reason_code,
            indexed_content_hash: None,
            current_content_hash: None,
            diagnostics: Vec::new(),
            limitations: Vec::new(),
            gradle_projects: Vec::new(),
            source_sets: Vec::new(),
            ownership: RepositoryFileOwnership {
                gradle_projects: BTreeSet::new(),
                source_sets: BTreeSet::new(),
            },
        }
    }
}
