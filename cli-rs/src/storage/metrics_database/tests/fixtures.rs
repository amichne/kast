    #[test]
    fn metrics_connection_applies_read_only_pragmas() {
        let fixture = seed_fixture();
        let request = fixture.request("fanIn", None, 10, 1);
        let db = MetricsDatabase::open(&request).expect("open metrics db");

        assert_eq!(pragma_i64(&db.conn, "query_only"), 1);
        assert_eq!(pragma_i64(&db.conn, "mmap_size"), 268_435_456);
        assert_eq!(pragma_i64(&db.conn, "cache_size"), -64_000);
        assert_eq!(pragma_i64(&db.conn, "temp_store"), 2);
        assert_eq!(pragma_i64(&db.conn, "busy_timeout"), 5_000);
    }

    fn strings(value: Value) -> Vec<String> {
        serde_json::from_value(value).expect("string array")
    }

    fn pragma_i64(conn: &Connection, name: &str) -> i64 {
        conn.query_row(&format!("PRAGMA {name}"), [], |row| row.get(0))
            .expect("pragma")
    }

    fn seed_fixture() -> Fixture {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let database = workspace.join(".gradle/kast/cache/source-index.db");
        std::fs::create_dir_all(database.parent().expect("db parent")).expect("db parent");
        seed_source_files(&workspace);
        let conn = Connection::open(&database).expect("sqlite");
        seed_schema(&conn);
        seed_rows(&conn);
        drop(conn);
        Fixture {
            _temp: temp,
            workspace,
            database,
        }
    }

    fn seed_schema(conn: &Connection) {
        conn.execute_batch(&format!(
            r#"
            CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0, head_commit TEXT);
            INSERT INTO schema_version (version, generation, head_commit) VALUES ({}, 0, NULL);
            CREATE TABLE path_prefixes (prefix_id INTEGER PRIMARY KEY, dir_path TEXT NOT NULL UNIQUE);
            CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE);
            CREATE VIRTUAL TABLE fq_names_fts USING fts5(fq_name, tokenize='trigram');
            CREATE TRIGGER fq_names_ai AFTER INSERT ON fq_names BEGIN
                INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
            END;
            CREATE TRIGGER fq_names_ad AFTER DELETE ON fq_names BEGIN
                DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
            END;
            CREATE TRIGGER fq_names_au AFTER UPDATE OF fq_name ON fq_names BEGIN
                DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
                INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
            END;
            CREATE TABLE identifier_paths (identifier TEXT NOT NULL, prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, PRIMARY KEY (identifier, prefix_id, filename));
            CREATE TABLE file_metadata (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, package_fq_id INTEGER, module_path TEXT, source_set TEXT, PRIMARY KEY (prefix_id, filename));
            CREATE TABLE file_manifest (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, last_modified_millis INTEGER NOT NULL, PRIMARY KEY (prefix_id, filename));
            CREATE TABLE declarations (
                fq_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                visibility TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                declaration_offset INTEGER,
                module_path TEXT,
                source_set TEXT,
                PRIMARY KEY (fq_id, prefix_id, filename)
            );
            CREATE TABLE symbol_references (
                src_prefix_id INTEGER NOT NULL,
                src_filename TEXT NOT NULL,
                source_offset INTEGER NOT NULL,
                source_fq_id INTEGER,
                target_fq_id INTEGER NOT NULL,
                tgt_prefix_id INTEGER,
                tgt_filename TEXT,
                target_offset INTEGER,
                edge_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
            );
            "#,
            SOURCE_INDEX_SCHEMA_VERSION,
        ))
        .expect("schema");
    }

    fn seed_rows(conn: &Connection) {
        conn.execute("INSERT INTO path_prefixes VALUES (1, 'app')", [])
            .expect("app prefix");
        conn.execute("INSERT INTO path_prefixes VALUES (2, 'lib')", [])
            .expect("lib prefix");
        for (id, name) in [
            (1, "app.A"),
            (2, "app.B"),
            (3, "app.C"),
            (4, "lib.Foo"),
            (5, "lib.FooWidget"),
            (6, "lib.Target"),
            (7, "lib.Popular"),
        ] {
            conn.execute(
                "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
                params![id, name],
            )
            .expect("fq name");
        }
        for (prefix, filename, module) in [
            (1, "A.kt", ":app"),
            (1, "B.kt", ":app"),
            (1, "C.kt", ":app"),
            (2, "Foo.kt", ":lib"),
            (2, "FooWidget.kt", ":lib"),
            (2, "Target.kt", ":lib"),
            (2, "Popular.kt", ":lib"),
        ] {
            conn.execute(
                "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (?, ?, ?, 'main')",
                params![prefix, filename, module],
            )
            .expect("file metadata");
            conn.execute(
                "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (?, ?, 1)",
                params![prefix, filename],
            )
            .expect("file manifest");
            conn.execute(
                "INSERT INTO identifier_paths(identifier, prefix_id, filename) VALUES (?, ?, ?)",
                params![filename.trim_end_matches(".kt"), prefix, filename],
            )
            .expect("identifier path");
        }
        for (fq_id, prefix, filename, module) in [
            (1, 1, "A.kt", ":app"),
            (2, 1, "B.kt", ":app"),
            (3, 1, "C.kt", ":app"),
            (4, 2, "Foo.kt", ":lib"),
            (5, 2, "FooWidget.kt", ":lib"),
            (6, 2, "Target.kt", ":lib"),
            (7, 2, "Popular.kt", ":lib"),
        ] {
            conn.execute(
                "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, 'CLASS', 'PUBLIC', ?, ?, 1, ?, 'main')",
                params![fq_id, prefix, filename, module],
            )
            .expect("declaration");
        }

        insert_ref(conn, 1, "B.kt", 10, 2, 6, 2, "Target.kt", "CALL");
        insert_ref(conn, 1, "A.kt", 11, 1, 4, 2, "Foo.kt", "CALL");
        insert_ref(conn, 1, "A.kt", 12, 1, 5, 2, "FooWidget.kt", "CALL");
        for offset in 100..130 {
            insert_ref(conn, 1, "A.kt", offset, 1, 7, 2, "Popular.kt", "CALL");
        }
        for offset in 200..230 {
            insert_ref(conn, 1, "B.kt", offset, 2, 7, 2, "Popular.kt", "CALL");
        }
        for offset in 300..330 {
            insert_ref(conn, 1, "C.kt", offset, 3, 7, 2, "Popular.kt", "CALL");
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn insert_ref(
        conn: &Connection,
        src_prefix: i64,
        src_filename: &str,
        offset: i64,
        source_fq_id: i64,
        target_fq_id: i64,
        tgt_prefix: i64,
        tgt_filename: &str,
        edge_kind: &str,
    ) {
        conn.execute(
            "INSERT INTO symbol_references(src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, tgt_prefix_id, tgt_filename, target_offset, edge_kind) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
            params![src_prefix, src_filename, offset, source_fq_id, target_fq_id, tgt_prefix, tgt_filename, edge_kind],
        )
        .expect("reference");
    }

    fn seed_source_files(workspace: &Path) {
        std::fs::create_dir_all(workspace.join("app")).expect("app sources");
        std::fs::create_dir_all(workspace.join("lib")).expect("lib sources");
        for path in [
            "app/A.kt",
            "app/B.kt",
            "app/C.kt",
            "lib/Foo.kt",
            "lib/FooWidget.kt",
            "lib/Target.kt",
            "lib/Popular.kt",
        ] {
            std::fs::write(workspace.join(path), "class Placeholder\n").expect("source file");
        }
    }
