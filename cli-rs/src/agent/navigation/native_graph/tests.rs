#[cfg(test)]
mod native_graph_tests {
    use super::*;

    fn fixture(node_count: usize, edges: &[(usize, usize, f64)]) -> NativeGraph {
        native_graph_to_csr(
            (0..node_count)
                .map(|node| NativeGraphNode {
                    database_id: Some(node as u64 + 1),
                    key: format!("n{node}"),
                })
                .collect(),
            edges
                .iter()
                .map(|&(source, target, weight)| NativeGraphEdge {
                    source,
                    target,
                    occurrence_count: 1,
                    weight,
                })
                .collect(),
        )
    }

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
                 INSERT INTO schema_version VALUES (11, 7);
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
                 INSERT INTO schema_version VALUES (11, 9);
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

    #[test]
    fn native_graph_tarjan_condensation_and_components_are_deterministic() {
        let graph = fixture(
            6,
            &[
                (0, 1, 1.0),
                (1, 0, 1.0),
                (1, 2, 1.0),
                (2, 3, 1.0),
                (3, 2, 1.0),
                (4, 5, 1.0),
            ],
        );
        assert_eq!(native_connected_components(&graph), vec![0, 0, 0, 0, 1, 1]);
        let first = native_tarjan_scc(&graph);
        assert_eq!(first, native_tarjan_scc(&graph));
        assert_eq!(
            native_condensation_topological_order(&graph, &first),
            native_condensation_topological_order(&graph, &first)
        );
    }

    #[test]
    fn native_graph_tarjan_handles_deep_acyclic_chain_without_process_stack_growth() {
        const CHILD_ENV: &str = "KAST_NATIVE_GRAPH_DEEP_TARJAN_CHILD";
        if std::env::var_os(CHILD_ENV).is_some() {
            let node_count = 50_000;
            let edges = (0..node_count - 1)
                .map(|node| (node, node + 1, 1.0))
                .collect::<Vec<_>>();
            let membership = native_tarjan_scc(&fixture(node_count, &edges));
            assert_eq!(membership.len(), node_count);
            return;
        }

        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "agent::native_graph_tests::native_graph_tarjan_handles_deep_acyclic_chain_without_process_stack_growth",
            ])
            .env(CHILD_ENV, "1")
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "deep Tarjan child failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    #[test]
    fn native_graph_weighted_leiden_is_deterministic_and_keeps_refined_communities_connected() {
        let graph = fixture(
            6,
            &[
                (0, 1, 10.0),
                (1, 2, 10.0),
                (2, 0, 10.0),
                (3, 4, 10.0),
                (4, 5, 10.0),
                (5, 3, 10.0),
                (2, 3, 0.1),
            ],
        );
        let first = native_weighted_leiden(&graph, 1.0);
        assert_eq!(first, native_weighted_leiden(&graph, 1.0));
        assert_eq!(first[0], first[1]);
        assert_eq!(first[1], first[2]);
        assert_eq!(first[3], first[4]);
        assert_eq!(first[4], first[5]);
        assert_ne!(first[2], first[3]);
    }

    #[test]
    fn native_graph_preserves_parallel_typed_edge_occurrence_weight() {
        let graph = fixture(2, &[(0, 1, 2.0), (0, 1, 3.0)]);
        assert_eq!(graph.edges.len(), 2);
        assert_eq!(graph.edges.iter().map(|edge| edge.occurrence_count).sum::<usize>(), 2);
        assert_eq!(graph.offsets, vec![0, 1, 1]);
        assert_eq!(graph.targets, vec![1]);
        assert_eq!(graph.edges.iter().map(|edge| edge.weight).sum::<f64>(), 5.0);
    }

    #[test]
    fn native_graph_package_scope_includes_root_package_files() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        connection
            .execute_batch(
                "ATTACH DATABASE ':memory:' AS repository_base;
                 CREATE TABLE semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                     refresh_status TEXT
                 );
                 CREATE TABLE semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
                 );
                 CREATE TABLE semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                     source_file_id INTEGER, kind TEXT, context TEXT
                 );
                 CREATE VIEW semantic_package_quotient AS
                     SELECT source_file.package_name AS source_container,
                            target_file.package_name AS target_container,
                            edges.kind, edges.context, COUNT(*) AS weight
                     FROM semantic_edge_occurrences edges
                     JOIN semantic_symbols source ON source.id = edges.source_id
                     JOIN semantic_symbols target ON target.id = edges.target_id
                     JOIN semantic_files source_file ON source_file.id = source.file_id
                     JOIN semantic_files target_file ON target_file.id = target.file_id
                     WHERE source_file.package_name IS NOT NULL
                       AND target_file.package_name IS NOT NULL
                     GROUP BY 1, 2, edges.kind, edges.context;
                 CREATE TABLE repository_overlay_tombstones(path TEXT PRIMARY KEY) WITHOUT ROWID;
                 CREATE TABLE repository_base.semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                     refresh_status TEXT
                 );
                 CREATE TABLE repository_base.semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
                 );
                 CREATE TABLE repository_base.semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                     source_file_id INTEGER, kind TEXT, context TEXT
                 );
                 INSERT INTO semantic_files VALUES
                     (1, 'Root.kt', NULL, 'main', 'REFRESHED'),
                     (2, 'Named.kt', 'demo', 'main', 'REFRESHED');
                 INSERT INTO semantic_symbols VALUES
                     (1, 'root', 'CLASS', 'Root', 1),
                     (2, 'named', 'CLASS', 'Named', 2);
                 INSERT INTO semantic_edge_occurrences VALUES
                     (1, 1, 2, 1, 'REFERENCES', 'NONE'),
                     (2, 2, 1, 2, 'REFERENCES', 'NONE'),
                     (3, 1, 2, 1, 'CALLS', 'DIRECT'),
                     (4, 1, 2, 1, 'REFERENCES', 'NONE');",
            )
            .unwrap();

        for graph in [
            load_native_graph(&connection, NativeGraphScope::Package, false).unwrap(),
            load_native_overlay_graph(&connection, NativeGraphScope::Package).unwrap(),
        ] {
            assert_eq!(
                graph
                    .nodes
                    .iter()
                    .map(|node| node.key.as_str())
                    .collect::<Vec<_>>(),
                vec!["<root>", "demo"]
            );
            assert_eq!(
                graph
                    .edges
                    .iter()
                    .map(|edge| (
                        graph.nodes[edge.source].key.as_str(),
                        graph.nodes[edge.target].key.as_str(),
                        edge.occurrence_count,
                        edge.weight,
                    ))
                    .collect::<Vec<_>>(),
                vec![
                    ("<root>", "demo", 2, 3.0),
                    ("demo", "<root>", 1, 1.0),
                ]
            );
        }
    }

    #[test]
    fn native_graph_package_scope_excludes_cached_boundary_placeholders() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        connection
            .execute_batch(
                "ATTACH DATABASE ':memory:' AS repository_base;
                 CREATE TABLE semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                     refresh_status TEXT
                 );
                 CREATE TABLE semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
                 );
                 CREATE TABLE semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                     source_file_id INTEGER, kind TEXT, context TEXT
                 );
                 CREATE TABLE repository_overlay_tombstones(path TEXT PRIMARY KEY) WITHOUT ROWID;
                 CREATE TABLE repository_base.semantic_files(
                     id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                     refresh_status TEXT
                 );
                 CREATE TABLE repository_base.semantic_symbols(
                     id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
                 );
                 CREATE TABLE repository_base.semantic_edge_occurrences(
                     id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                     source_file_id INTEGER, kind TEXT, context TEXT
                 );
                 INSERT INTO semantic_files VALUES
                     (1, 'Named.kt', 'demo', 'main', 'REFRESHED'),
                     (2, 'Boundary.kt', NULL, NULL, 'CACHED');
                 INSERT INTO semantic_symbols VALUES
                     (1, 'named', 'FUNCTION', 'named', 1),
                     (2, 'boundary', 'CLASS', 'Boundary', 2);
                 INSERT INTO semantic_edge_occurrences VALUES
                     (1, 1, 2, 1, 'REFERENCES', 'NONE');",
            )
            .unwrap();

        for graph in [
            load_native_graph(&connection, NativeGraphScope::Package, false).unwrap(),
            load_native_overlay_graph(&connection, NativeGraphScope::Package).unwrap(),
        ] {
            assert_eq!(
                graph
                    .nodes
                    .iter()
                    .map(|node| node.key.as_str())
                    .collect::<Vec<_>>(),
                vec!["demo"]
            );
            assert!(graph.edges.is_empty());
        }
    }

    #[test]
    fn native_graph_base_plus_overlay_equals_clean_rebuild() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        connection.execute_batch(
            "ATTACH DATABASE ':memory:' AS repository_base;
             CREATE TABLE semantic_files(
                 id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                 refresh_status TEXT
             );
             CREATE TABLE semantic_symbols(
                 id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
             );
             CREATE TABLE semantic_edge_occurrences(
                 id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                 source_file_id INTEGER, kind TEXT, context TEXT
             );
             CREATE TABLE repository_overlay_tombstones(path TEXT PRIMARY KEY) WITHOUT ROWID;
             CREATE TABLE repository_base.semantic_files(
                 id INTEGER PRIMARY KEY, path TEXT, package_name TEXT, module_name TEXT,
                 refresh_status TEXT
             );
             CREATE TABLE repository_base.semantic_symbols(
                 id INTEGER PRIMARY KEY, stable_key TEXT, kind TEXT, name TEXT, file_id INTEGER
             );
             CREATE TABLE repository_base.semantic_edge_occurrences(
                 id INTEGER PRIMARY KEY, source_id INTEGER, target_id INTEGER,
                 source_file_id INTEGER, kind TEXT, context TEXT
             );
             INSERT INTO repository_base.semantic_files VALUES
                 (1, 'A.kt', 'demo', 'main', 'REFRESHED'),
                 (2, 'B.kt', 'demo', 'main', 'REFRESHED');
             INSERT INTO repository_base.semantic_symbols VALUES
                 (1, 'old', 'CLASS', 'Old', 1),
                 (2, 'b', 'CLASS', 'B', 2);
             INSERT INTO repository_base.semantic_edge_occurrences VALUES
                 (1, 1, 2, 1, 'REFERENCES', 'NONE');
             INSERT INTO repository_overlay_tombstones VALUES ('A.kt');
             INSERT INTO semantic_files VALUES
                 (1, 'A.kt', 'demo', 'main', 'REFRESHED');
             INSERT INTO semantic_symbols VALUES
                 (1, 'new', 'CLASS', 'New', 1),
                 (2, 'b', 'CLASS', 'B', 2);
             INSERT INTO semantic_files VALUES
                 (2, 'B.kt', NULL, NULL, 'CACHED');
             INSERT INTO semantic_edge_occurrences VALUES
                 (1, 1, 2, 1, 'REFERENCES', 'NONE');",
        )
        .unwrap();

        let tombstoned = load_native_overlay_graph(&connection, NativeGraphScope::Symbol).unwrap();
        assert_eq!(
            tombstoned
                .nodes
                .iter()
                .map(|node| node.key.as_str())
                .collect::<Vec<_>>(),
            vec!["b"]
        );
        assert!(tombstoned.edges.is_empty());
        connection
            .execute(
                "DELETE FROM repository_overlay_tombstones WHERE path = 'A.kt'",
                [],
            )
            .unwrap();

        let overlay = load_native_overlay_graph(&connection, NativeGraphScope::Symbol).unwrap();
        let clean = native_graph_to_csr(
            vec![
                NativeGraphNode {
                    database_id: None,
                    key: "b".to_string(),
                },
                NativeGraphNode {
                    database_id: None,
                    key: "new".to_string(),
                },
            ],
            vec![NativeGraphEdge {
                source: 1,
                target: 0,
                occurrence_count: 1,
                weight: 1.0,
            }],
        );

        assert_eq!(
            overlay
                .nodes
                .iter()
                .map(|node| &node.key)
                .collect::<Vec<_>>(),
            clean
                .nodes
                .iter()
                .map(|node| &node.key)
                .collect::<Vec<_>>()
        );
        assert_eq!(overlay.offsets, clean.offsets);
        assert_eq!(overlay.targets, clean.targets);
        assert_eq!(
            overlay
                .edges
                .iter()
                .map(|edge| (edge.source, edge.target, edge.occurrence_count, edge.weight))
                .collect::<Vec<_>>(),
            clean
                .edges
                .iter()
                .map(|edge| (edge.source, edge.target, edge.occurrence_count, edge.weight))
                .collect::<Vec<_>>(),
        );
    }

    #[cfg(unix)]
    #[test]
    fn native_graph_reports_process_peak_rss() {
        assert!(native_graph_peak_rss_bytes() > 0);
    }
}
