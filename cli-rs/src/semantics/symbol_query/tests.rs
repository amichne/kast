#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::hooks::{AuthAction, AuthContext, Authorization};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};

    #[test]
    fn lexical_tokens_split_package_punctuation_snake_and_camel_boundaries() {
        let tokens =
            lexical_tokens("io.github.payments.CardPaymentProcessor card_payment_processor.kt");

        assert_eq!(
            tokens,
            vec![
                "io",
                "github",
                "payments",
                "card",
                "payment",
                "processor",
                "kt",
            ]
        );
    }

    #[test]
    fn lexical_tokens_lowercase_ascii_and_deduplicate_per_field() {
        let tokens = lexical_tokens("CardPaymentProcessor card CARD paymentProcessor");

        assert_eq!(tokens, vec!["card", "payment", "processor"]);
    }

    #[test]
    fn nonmatching_declarations_do_not_multiply_sql_reads() {
        let baseline = query_identifier_and_import_evidence(0, 0);
        let with_decoys = query_identifier_and_import_evidence(256, 0);

        assert_eq!(
            baseline.0,
            vec![
                "sample.IdentifierOnly".to_string(),
                "sample.DirectImportOnly".to_string(),
                "sample.WildcardImportOnly".to_string(),
            ],
        );
        assert_eq!(baseline.0, with_decoys.0);
        assert_eq!(
            baseline.1, with_decoys.1,
            "nonmatching declarations added {} prepared SELECT statements",
            with_decoys.1.saturating_sub(baseline.1),
        );
    }

    #[test]
    fn repeated_identifier_rows_do_not_expand_candidate_evidence() {
        let baseline = query_identifier_and_import_evidence(0, 0);
        let repeated = query_identifier_and_import_evidence(0, 1_024);

        assert_eq!(baseline.0, repeated.0, "candidate order");
        assert_eq!(baseline.3, repeated.3, "candidate rank");
        assert_eq!(repeated.2, 1, "one bounded identifier signal per query term");
    }

    #[test]
    fn filtered_lexical_evidence_does_not_scan_ineligible_files() {
        let baseline_steps = filtered_identifier_vm_steps(0);
        let noisy_steps = filtered_identifier_vm_steps(4_096);

        assert!(
            noisy_steps <= baseline_steps.saturating_mul(2),
            "ineligible files expanded lexical work from {baseline_steps} to {noisy_steps} VM steps",
        );
    }

    fn filtered_identifier_vm_steps(ineligible_file_count: usize) -> usize {
        let temp = tempfile::tempdir().expect("filtered symbol query tempdir");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&workspace).expect("filtered symbol query workspace");
        let database = temp.path().join("source-index.db");
        let mut conn = Connection::open(&database).expect("filtered symbol query database");
        conn.execute_batch(&format!(
            r#"
            CREATE TABLE schema_version(version INTEGER NOT NULL);
            INSERT INTO schema_version VALUES ({SOURCE_INDEX_SCHEMA_VERSION});
            CREATE TABLE path_prefixes(prefix_id INTEGER, dir_path TEXT);
            CREATE TABLE fq_names(fq_id INTEGER PRIMARY KEY, fq_name TEXT);
            CREATE TABLE symbol_references(source_fq_id INTEGER, target_fq_id INTEGER);
            CREATE TABLE file_metadata(prefix_id INTEGER, filename TEXT);
            CREATE TABLE file_manifest(prefix_id INTEGER, filename TEXT);
            CREATE TABLE declarations(fq_id INTEGER, prefix_id INTEGER, filename TEXT);
            CREATE TABLE identifier_paths(
                identifier TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL
            );
            CREATE INDEX idx_ip_prefix_file ON identifier_paths(prefix_id, filename);
            CREATE TABLE file_imports(
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY(prefix_id, filename, fq_id)
            );
            CREATE TABLE file_wildcard_imports(
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY(prefix_id, filename, fq_id)
            );
            INSERT INTO identifier_paths VALUES ('TargetIdentifier', 1, 'Eligible.kt');
            INSERT INTO fq_names VALUES
                (1, 'support.DirectIdentifier'),
                (2, 'support.WildcardIdentifier');
            INSERT INTO file_imports VALUES (1, 'Eligible.kt', 1);
            INSERT INTO file_wildcard_imports VALUES (1, 'Eligible.kt', 2);
            "#,
        ))
        .expect("filtered symbol query schema");
        let tx = conn.transaction().expect("filtered decoy transaction");
        for index in 0..ineligible_file_count {
            let fq_id = 1_000 + i64::try_from(index).expect("filtered decoy fq id");
            let filename = format!("Ineligible{index:04}.kt");
            tx.execute(
                "INSERT INTO identifier_paths VALUES (?, 2, ?)",
                params![format!("Identifier{index:04}"), filename],
            )
            .expect("filtered decoy identifier");
            tx.execute(
                "INSERT INTO fq_names VALUES (?, ?)",
                params![fq_id, format!("noise.Identifier{index:04}")],
            )
            .expect("filtered decoy FQ name");
            tx.execute(
                "INSERT INTO file_imports VALUES (2, ?, ?)",
                params![filename, fq_id],
            )
            .expect("filtered decoy import");
            tx.execute(
                "INSERT INTO file_wildcard_imports VALUES (2, ?, ?)",
                params![filename, fq_id],
            )
            .expect("filtered decoy wildcard import");
        }
        tx.commit().expect("filtered decoy commit");
        drop(conn);

        let db = SymbolQueryDatabase::open(&workspace, &database).expect("open filtered query");
        let vm_steps = Arc::new(AtomicUsize::new(0));
        let observed_steps = Arc::clone(&vm_steps);
        db.conn
            .progress_handler(
                1,
                Some(move || {
                    observed_steps.fetch_add(1, AtomicOrdering::Relaxed);
                    false
                }),
            )
            .expect("install VM step observer");
        let eligible_files = BTreeMap::from([(
            1,
            BTreeSet::from(["Eligible.kt".to_string()]),
        )]);
        let matches = db
            .lexical_matches_by_file(&["identifier".to_string()], &eligible_files)
            .expect("filtered lexical query");
        assert_eq!(
            matches
                .get(&1)
                .and_then(|files| files.get("Eligible.kt"))
                .map(BTreeMap::len),
            Some(2),
        );
        vm_steps.load(AtomicOrdering::Relaxed)
    }

    fn query_identifier_and_import_evidence(
        decoy_count: usize,
        repeated_identifier_count: usize,
    ) -> (Vec<String>, usize, usize, f64) {
        let temp = tempfile::tempdir().expect("symbol query tempdir");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&workspace).expect("symbol query workspace");
        let database = temp.path().join("source-index.db");
        let mut conn = Connection::open(&database).expect("symbol query database");
        conn.execute_batch(&format!(
            r#"
            CREATE TABLE schema_version(version INTEGER NOT NULL);
            INSERT INTO schema_version VALUES ({SOURCE_INDEX_SCHEMA_VERSION});
            CREATE TABLE path_prefixes(
                prefix_id INTEGER PRIMARY KEY,
                dir_path TEXT NOT NULL UNIQUE
            );
            CREATE TABLE fq_names(
                fq_id INTEGER PRIMARY KEY,
                fq_name TEXT NOT NULL UNIQUE
            );
            CREATE TABLE identifier_paths(
                identifier TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL
            );
            CREATE TABLE file_metadata(
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                package_fq_id INTEGER,
                module_path TEXT,
                source_set TEXT
            );
            CREATE TABLE file_manifest(
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                last_modified_millis INTEGER NOT NULL
            );
            CREATE TABLE declarations(
                fq_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                visibility TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                declaration_offset INTEGER,
                module_path TEXT,
                source_set TEXT
            );
            CREATE TABLE symbol_references(
                source_fq_id INTEGER,
                target_fq_id INTEGER
            );
            CREATE TABLE file_imports(
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL
            );
            CREATE TABLE file_wildcard_imports(
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL
            );
            INSERT INTO path_prefixes VALUES (1, 'src');
            INSERT INTO fq_names VALUES
                (1, 'sample.IdentifierOnly'),
                (2, 'sample.DirectImportOnly'),
                (3, 'sample.WildcardImportOnly'),
                (4, 'support.NeedleDirect'),
                (5, 'support.NeedleWildcard');
            INSERT INTO declarations VALUES
                (1, 'CLASS', 'PUBLIC', 1, 'IdentifierOnly.kt', 1, ':app', 'main'),
                (2, 'CLASS', 'PUBLIC', 1, 'DirectOnly.kt', 1, ':app', 'main'),
                (3, 'CLASS', 'PUBLIC', 1, 'WildcardOnly.kt', 1, ':app', 'main');
            INSERT INTO identifier_paths VALUES ('NeedleIdentifier', 1, 'IdentifierOnly.kt');
            INSERT INTO file_imports VALUES (1, 'DirectOnly.kt', 4);
            INSERT INTO file_wildcard_imports VALUES (1, 'WildcardOnly.kt', 5);
            "#
        ))
        .expect("symbol query schema");
        let tx = conn.transaction().expect("decoy transaction");
        for index in 0..decoy_count {
            let fq_id = 1_000 + i64::try_from(index).expect("decoy fq id");
            let fq_name = format!("sample.Decoy{index:04}");
            tx.execute(
                "INSERT INTO fq_names VALUES (?, ?)",
                params![fq_id, fq_name],
            )
            .expect("decoy fq name");
            tx.execute(
                "INSERT INTO declarations VALUES (?, 'CLASS', 'PUBLIC', 1, 'Decoys.kt', 1, ':app', 'main')",
                params![fq_id],
            )
            .expect("decoy declaration");
        }
        for index in 0..repeated_identifier_count {
            tx.execute(
                "INSERT INTO identifier_paths VALUES (?, 1, 'IdentifierOnly.kt')",
                params![format!("NeedleIdentifier{index:04}")],
            )
            .expect("repeated identifier evidence");
        }
        tx.commit().expect("decoy commit");
        drop(conn);

        let db = SymbolQueryDatabase::open(&workspace, &database).expect("open symbol query");
        let select_count = Arc::new(AtomicUsize::new(0));
        let observed_select_count = Arc::clone(&select_count);
        db.conn
            .authorizer(Some(move |context: AuthContext<'_>| {
                if matches!(context.action, AuthAction::Select) {
                    observed_select_count.fetch_add(1, AtomicOrdering::Relaxed);
                }
                Authorization::Allow
            }))
            .expect("install query observer");
        let result = db
            .query(SymbolQueryRequest {
                query: "Needle".to_string(),
                modes: vec!["lexical".to_string()],
                filters: SymbolQueryFilters::default(),
                anchor: SymbolQueryAnchor::default(),
                graph: SymbolQueryGraph::default(),
                semantic: SymbolQuerySemantic::default(),
                limit: 25,
                include_next_requests: false,
            })
            .expect("symbol query");

        let identifier_signal_count = result
            .results
            .iter()
            .find(|candidate| candidate.declaration.fq_name == "sample.IdentifierOnly")
            .expect("identifier candidate")
            .signals
            .lexical
            .matches
            .len();
        let identifier_rank = result
            .results
            .iter()
            .find(|candidate| candidate.declaration.fq_name == "sample.IdentifierOnly")
            .expect("identifier candidate")
            .rank
            .components
            .lexical;
        (
            result
                .results
                .into_iter()
                .map(|candidate| candidate.declaration.fq_name)
                .collect(),
            select_count.load(AtomicOrdering::Relaxed),
            identifier_signal_count,
            identifier_rank,
        )
    }
}
