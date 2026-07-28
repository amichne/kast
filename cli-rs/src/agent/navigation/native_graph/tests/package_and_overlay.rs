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
