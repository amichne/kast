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
        let baseline = query_identifier_and_import_evidence(0);
        let with_decoys = query_identifier_and_import_evidence(256);

        assert_eq!(baseline.0, with_decoys.0);
        assert_eq!(
            baseline.1, with_decoys.1,
            "nonmatching declarations added {} prepared SELECT statements",
            with_decoys.1.saturating_sub(baseline.1),
        );
    }

    fn query_identifier_and_import_evidence(decoy_count: usize) -> (Vec<String>, usize) {
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

        (
            result
                .results
                .into_iter()
                .map(|candidate| candidate.declaration.fq_name)
                .collect(),
            select_count.load(AtomicOrdering::Relaxed),
        )
    }
}
