    #[test]
    fn native_graph_resumed_nodes_require_generation_before_database_access() {
        let temp = tempfile::tempdir().unwrap();
        let args = AgentNativeGraphArgs {
            runtime: AgentRuntimeArgs::default(),
            database: Some(temp.path().join("missing.db")),
            scope: Some(NativeGraphScope::Symbol),
            operation: NativeGraphOperation::Nodes,
            file_paths: Vec::new(),
            removed_file_paths: Vec::new(),
            modules: Vec::new(),
            source_sets: Vec::new(),
            exclusive: false,
            symbol: None,
            generation: None,
            after_id: Some(1),
            limit: Some(100),
            resolution: None,
        };

        let error = native_graph_result(&args).unwrap_err();

        assert_eq!(error.code, "AGENT_USAGE");
        assert!(error.message.contains("--generation"));
    }

    #[test]
    fn native_graph_ignores_legacy_overlay_descriptor_without_repository_base() {
        let temp = tempfile::tempdir().unwrap();
        let database = temp.path().join("source-index.db");
        let connection = rusqlite::Connection::open(&database).unwrap();
        std::fs::write(temp.path().join("repository-overlay.json"), "{}").unwrap();

        assert!(!native_graph_attach_repository_base(&connection, &database).unwrap());
    }

    #[test]
    fn native_graph_neighbors_reads_only_incident_symbol_edges() {
        let mut connection = rusqlite::Connection::open_in_memory().unwrap();
        connection
            .execute_batch(
                "CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL);
                 INSERT INTO schema_version VALUES (12, 7);
                 CREATE TABLE semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, package_name TEXT,
                     module_name TEXT, refresh_status TEXT NOT NULL
                 );
                 CREATE TABLE semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT NOT NULL UNIQUE, file_id INTEGER NOT NULL,
                     kind TEXT NOT NULL, name TEXT NOT NULL
                 );
                 CREATE TABLE semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL, target_id INTEGER NOT NULL,
                     source_file_id INTEGER NOT NULL, kind TEXT NOT NULL, context TEXT NOT NULL
                 );
                 CREATE INDEX idx_semantic_edges_source_kind_target
                     ON semantic_edge_occurrences(source_id, kind, target_id);
                 CREATE INDEX idx_semantic_edges_target_kind_source
                     ON semantic_edge_occurrences(target_id, kind, source_id);
                 INSERT INTO semantic_files VALUES (1, 'Target.kt', 'demo', 'main', 'REFRESHED');
                 INSERT INTO semantic_files VALUES (2, 'Neighbor.kt', 'demo', 'main', 'REFRESHED');
                 INSERT INTO semantic_symbols VALUES (1, 'target', 1, 'CLASS', 'Target');
                 INSERT INTO semantic_symbols VALUES (2, 'neighbor', 2, 'CLASS', 'Neighbor');
                 INSERT INTO semantic_edge_occurrences
                     VALUES (1, 1, 2, 1, 'REFERENCES', 'NONE');
                 INSERT INTO semantic_edge_occurrences
                     VALUES (2, 2, 1, 2, 'CALLS', 'BODY');",
            )
            .unwrap();
        let transaction = connection.transaction().unwrap();
        for id in 3..=2_002 {
            transaction
                .execute(
                    "INSERT INTO semantic_files VALUES (?, ?, 'unrelated', 'other', 'REFRESHED')",
                    rusqlite::params![id, format!("Unrelated{id}.kt")],
                )
                .unwrap();
            transaction
                .execute(
                    "INSERT INTO semantic_symbols VALUES (?, ?, ?, 'CLASS', ?)",
                    rusqlite::params![id, format!("unrelated-{id}"), id, format!("Unrelated{id}")],
                )
                .unwrap();
            transaction
                .execute(
                    "INSERT INTO semantic_edge_occurrences VALUES (?, ?, ?, ?, 'REFERENCES', 'NONE')",
                    rusqlite::params![id, id, id, id],
                )
                .unwrap();
        }
        transaction.commit().unwrap();
        let vm_steps = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let observed_steps = std::sync::Arc::clone(&vm_steps);
        connection
            .progress_handler(
                1,
                Some(move || {
                    observed_steps.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    false
                }),
            )
            .unwrap();

        let result = native_graph_neighbors(
            &connection,
            7,
            NativeGraphScope::Symbol,
            "target",
            false,
        )
        .unwrap();

        assert_eq!(
            (
                result["outgoing"][0]["target"].as_str(),
                result["incoming"][0]["source"].as_str(),
                result["generation"].as_u64(),
            ),
            (Some("neighbor"), Some("neighbor"), Some(7)),
        );
        assert!(
            vm_steps.load(std::sync::atomic::Ordering::Relaxed) < 10_000,
            "neighbors query executed {} SQLite VM steps for a degree-two node",
            vm_steps.load(std::sync::atomic::Ordering::Relaxed),
        );
    }

    #[test]
    fn native_graph_neighbors_preserves_container_quotient_weights() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        connection
            .execute_batch(
                "CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL);
                 INSERT INTO schema_version VALUES (12, 9);
                 CREATE TABLE semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, package_name TEXT,
                     module_name TEXT, refresh_status TEXT NOT NULL
                 );
                 CREATE TABLE semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT NOT NULL UNIQUE, file_id INTEGER NOT NULL,
                     kind TEXT NOT NULL, name TEXT NOT NULL
                 );
                 CREATE INDEX idx_semantic_symbols_file_id_id
                     ON semantic_symbols(file_id, id);
                 CREATE TABLE semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL, target_id INTEGER NOT NULL,
                     source_file_id INTEGER NOT NULL, kind TEXT NOT NULL, context TEXT NOT NULL
                 );
                 CREATE INDEX idx_semantic_edges_source_kind_target
                     ON semantic_edge_occurrences(source_id, kind, target_id);
                 CREATE INDEX idx_semantic_edges_target_kind_source
                     ON semantic_edge_occurrences(target_id, kind, source_id);
                 INSERT INTO semantic_files VALUES
                     (1, 'Target.kt', 'alpha', 'app', 'REFRESHED'),
                     (2, 'Neighbor.kt', 'beta', 'lib', 'REFRESHED'),
                     (3, 'Root.kt', NULL, 'root', 'REFRESHED');
                 INSERT INTO semantic_symbols VALUES
                     (1, 'target-a', 1, 'CLASS', 'TargetA'),
                     (2, 'target-b', 1, 'CLASS', 'TargetB'),
                     (3, 'neighbor', 2, 'CLASS', 'Neighbor'),
                     (4, 'root', 3, 'CLASS', 'Root');
                 INSERT INTO semantic_edge_occurrences VALUES
                     (1, 1, 3, 1, 'REFERENCES', 'NONE'),
                     (2, 2, 3, 1, 'REFERENCES', 'NONE'),
                     (3, 3, 1, 2, 'CALLS', 'BODY'),
                     (4, 4, 3, 3, 'REFERENCES', 'NONE');",
            )
            .unwrap();

        for (scope, key, adjacent) in [
            (NativeGraphScope::File, "Target.kt", "Neighbor.kt"),
            (NativeGraphScope::Package, "alpha", "beta"),
            (NativeGraphScope::Module, "app", "lib"),
        ] {
            let result = native_graph_neighbors(&connection, 9, scope, key, false).unwrap();

            assert_eq!(result["outgoing"][0]["target"], adjacent, "{scope:?}");
            assert_eq!(result["outgoing"][0]["weight"], 2.0, "{scope:?}");
            assert_eq!(result["incoming"][0]["source"], adjacent, "{scope:?}");
            assert_eq!(result["incoming"][0]["weight"], 1.0, "{scope:?}");
        }
        let root = native_graph_neighbors(
            &connection,
            9,
            NativeGraphScope::Package,
            NATIVE_GRAPH_ROOT_PACKAGE_KEY,
            false,
        )
        .unwrap();
        assert_eq!(root["outgoing"][0]["target"], "beta");
    }
