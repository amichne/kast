mod support;

use rusqlite::params;
use sha2::{Digest, Sha256};
use support::workspace_files::WorkspaceIndexFixture;
use support::*;

fn coverage_fixture() -> (
    tempfile::TempDir,
    std::path::PathBuf,
    std::path::PathBuf,
    std::path::PathBuf,
    WorkspaceIndexFixture,
) {
    coverage_fixture_with_file_count(1)
}

fn coverage_fixture_with_file_count(
    file_count: usize,
) -> (
    tempfile::TempDir,
    std::path::PathBuf,
    std::path::PathBuf,
    std::path::PathBuf,
    WorkspaceIndexFixture,
) {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let data = default_install_root(&home).join("state/data/workspaces");
    std::fs::create_dir_all(data.join("local")).expect("workspace data");
    std::fs::write(
        data.join("local-workspaces.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            workspace.display().to_string(): "repository-intelligence"
        }))
        .expect("workspace registry JSON"),
    )
    .expect("workspace registry");
    let mut sanitized = String::new();
    for character in workspace.display().to_string().chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
            sanitized.push(character);
        } else if !sanitized.ends_with('-') {
            sanitized.push('-');
        }
    }
    let sanitized = sanitized
        .trim_matches('-')
        .chars()
        .take(80)
        .collect::<String>();
    let database = data
        .join("local")
        .join(format!("{sanitized}--repository-intelligence"))
        .join("cache/source-index.db");
    let fixture = WorkspaceIndexFixture::at_database_path(&workspace, &database);
    fixture.seed_high_cardinality_sources(file_count);
    let file_count = i64::try_from(file_count).expect("fixture file count");
    fixture.seed_progress("app", "COMPLETE", file_count, file_count);
    let connection = fixture.connection();
    connection
        .execute_batch(
            "CREATE TABLE semantic_files (
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                package_name TEXT,
                module_name TEXT,
                content_hash TEXT,
                refresh_status TEXT NOT NULL,
                diagnostics_json TEXT NOT NULL
            );",
        )
        .expect("semantic graph schema");
    for index in 0..file_count {
        let path = format!("src/main/kotlin/sample/Source{index:04}.kt");
        let content = std::fs::read(workspace.join(&path)).expect("Kotlin source");
        connection
            .execute(
            "INSERT INTO semantic_files(path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("semantic graph file");
    }
    (temp, home, config_home, workspace, fixture)
}

fn rpc(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    request: serde_json::Value,
) -> (std::process::ExitStatus, serde_json::Value) {
    let output = rpc_output(home, config_home, workspace, "json", &request);
    let response = serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "rpc JSON: {error}; stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    });
    (output.status, response)
}

fn rpc_output(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    output_format: &str,
    request: &serde_json::Value,
) -> std::process::Output {
    kast(home, config_home)
        .args([
            "--output",
            output_format,
            "rpc",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--request",
            &request.to_string(),
        ])
        .output()
        .expect("rpc")
}

fn graph_coverage_page_request(
    id: &str,
    continuation: Option<&str>,
    limit: usize,
) -> serde_json::Value {
    let mut params = serde_json::json!({
        "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"},
        "limit": limit
    });
    if let Some(continuation) = continuation {
        params["continuation"] = serde_json::json!(continuation);
    }
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "graph/coverage",
        "params": params
    })
}

struct AgentRepositoryTraversalRequest<'a> {
    question: &'a str,
    results: usize,
    module: Option<&'a str>,
    source_set: Option<&'a str>,
    continuation: Option<&'a str>,
    verbose: bool,
}

impl<'a> AgentRepositoryTraversalRequest<'a> {
    fn new(question: &'a str) -> Self {
        Self {
            question,
            results: 10,
            module: None,
            source_set: None,
            continuation: None,
            verbose: false,
        }
    }
}

fn agent_repository_traversal_page(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    request: AgentRepositoryTraversalRequest<'_>,
) -> (std::process::ExitStatus, serde_json::Value) {
    let mut args = vec![
        "agent".to_string(),
        "repository".to_string(),
        "--workspace-root".to_string(),
        workspace.to_str().expect("workspace").to_string(),
        "--question".to_string(),
        request.question.to_string(),
        "--intent".to_string(),
        "outgoing-impact".to_string(),
        "--language".to_string(),
        "kotlin".to_string(),
        "--relation".to_string(),
        "calls".to_string(),
        "--max-depth".to_string(),
        "2".to_string(),
        "--depth".to_string(),
        "2".to_string(),
        "--results".to_string(),
        request.results.to_string(),
        "--evidence".to_string(),
        "5".to_string(),
    ];
    if let Some(module) = request.module {
        args.extend(["--module".to_string(), module.to_string()]);
    }
    if let Some(source_set) = request.source_set {
        args.extend(["--source-set".to_string(), source_set.to_string()]);
    }
    if let Some(continuation) = request.continuation {
        args.extend(["--continuation".to_string(), continuation.to_string()]);
    }
    if request.verbose {
        args.push("--verbose".to_string());
    }
    let output = kast(home, config_home)
        .args(args)
        .output()
        .expect("agent repository traversal page");
    let raw = String::from_utf8(output.stdout).expect("agent repository traversal UTF-8");
    let response = toon_format::decode_default(raw.trim()).unwrap_or_else(|error| {
        panic!(
            "agent repository traversal TOON: {error}; stdout={raw} stderr={}",
            String::from_utf8_lossy(&output.stderr)
        )
    });
    (output.status, response)
}

fn repository_relationship_identities(
    response: &serde_json::Value,
) -> std::collections::BTreeSet<(String, String, String, String)> {
    response["result"]["relationships"]
        .as_array()
        .expect("repository relationships")
        .iter()
        .map(|relationship| {
            (
                relationship["sourceKey"]
                    .as_str()
                    .expect("relationship source")
                    .to_string(),
                relationship["targetKey"]
                    .as_str()
                    .expect("relationship target")
                    .to_string(),
                relationship["kind"]
                    .as_str()
                    .expect("relationship kind")
                    .to_string(),
                relationship["context"]
                    .as_str()
                    .expect("relationship context")
                    .to_string(),
            )
        })
        .collect()
}

fn repository_path_page_request(
    id: &str,
    continuation: serde_json::Value,
    evidence_limit: usize,
) -> serde_json::Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "repository/query",
        "params": {
            "question": "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
            "intent": "path",
            "scope": {
                "language": "kotlin",
                "module": null,
                "sourceSet": null,
                "relations": ["CALLS"],
                "direction": "OUTGOING",
                "maxDepth": null
            },
            "limits": {"depth": 6, "results": 10, "evidence": evidence_limit},
            "evidenceContinuation": continuation
        }
    })
}

fn seed_repository_graph(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute_batch(
            "
            CREATE TABLE semantic_types (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                classifier TEXT,
                nullability TEXT NOT NULL,
                debug_text TEXT NOT NULL,
                flexible_lower_id INTEGER,
                flexible_upper_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER
            );
            CREATE TABLE semantic_symbols (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                file_id INTEGER NOT NULL,
                owner_id INTEGER,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                fq_name TEXT,
                signature TEXT,
                visibility TEXT NOT NULL DEFAULT 'PUBLIC',
                modality TEXT,
                origin TEXT NOT NULL DEFAULT 'SOURCE',
                is_expect INTEGER NOT NULL DEFAULT 0,
                is_actual INTEGER NOT NULL DEFAULT 0,
                is_override INTEGER NOT NULL DEFAULT 0,
                is_sealed INTEGER NOT NULL DEFAULT 0,
                is_delegated INTEGER NOT NULL DEFAULT 0,
                declared_type_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL
            );
            CREATE TABLE semantic_symbol_annotations (
                symbol_id INTEGER NOT NULL,
                annotation_name TEXT NOT NULL,
                PRIMARY KEY(symbol_id, annotation_name)
            );
            CREATE TABLE semantic_edge_occurrences (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                source_file_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                context TEXT NOT NULL,
                resolved_target_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL
            );
            INSERT INTO semantic_types
                (id, stable_key, kind, classifier, nullability, debug_text)
                VALUES (1, 'type:kotlin.String', 'CLASS', 'kotlin.String', 'NON_NULL', 'String');
            INSERT INTO semantic_symbols
                (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, return_type_id, start_offset, end_offset, line)
                VALUES
                (1, 'class:SemanticGraphSha256', 1, NULL, 'CLASS', 'SemanticGraphSha256', 'sample.SemanticGraphSha256', NULL, NULL, 0, 100, 1),
                (2, 'object:SemanticGraphSha256.Companion', 1, 1, 'OBJECT', 'Companion', 'sample.SemanticGraphSha256.Companion', NULL, NULL, 10, 90, 2),
                (3, 'callable:semanticGraphOperation', 1, NULL, 'FUNCTION', 'semanticGraphOperation', 'sample.semanticGraphOperation', 'sample.semanticGraphOperation|-|||0', NULL, 100, 200, 10),
                (4, 'callable:buildSemanticGraphSnapshot', 1, NULL, 'FUNCTION', 'buildSemanticGraphSnapshot', 'sample.buildSemanticGraphSnapshot', 'sample.buildSemanticGraphSnapshot|-|||0', NULL, 210, 400, 20),
                (5, 'local:hash', 1, 4, 'PROPERTY', 'hash', NULL, NULL, NULL, 250, 300, 25),
                (6, 'callable:SemanticGraphSha256.parse', 1, 2, 'MEMBER_FUNCTION', 'parse', 'sample.SemanticGraphSha256.Companion.parse', 'sample.SemanticGraphSha256.Companion.parse|-||kotlin.String|0', 1, 40, 80, 4),
                (7, 'callable:calls', 1, NULL, 'FUNCTION', 'calls', 'sample.calls', 'sample.calls|-|||0', NULL, 410, 420, 41),
                (8, 'callable:other.parse', 1, NULL, 'FUNCTION', 'parse', 'sample.parse', 'sample.parse|-||kotlin.String|0', 1, 430, 440, 43),
                (9, 'callable:cycleTarget', 1, NULL, 'FUNCTION', 'cycleTarget', 'other.cycleTarget', 'other.cycleTarget|-|||0', NULL, 450, 470, 45);
            INSERT INTO semantic_edge_occurrences
                (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
                VALUES
                (1, 3, 4, 1, 'CALLS', 'CALL', 4, 150, 170, 15),
                (2, 4, 5, 1, 'CONTAINS', 'NONE', 5, 250, 300, 25),
                (3, 5, 6, 1, 'CALLS', 'CALL', 6, 270, 280, 27),
                (4, 5, 6, 1, 'CALLS', 'CALL', 6, 281, 290, 28),
                (5, 5, 6, 1, 'CALLS', 'CALL', 6, 291, 300, 29),
                (6, 3, 9, 1, 'CALLS', 'CALL', 9, 180, 190, 18),
                (7, 9, 3, 1, 'CALLS', 'CALL', 3, 460, 470, 46);
            ",
        )
        .expect("semantic graph facts");
}

fn seed_expect_actual_relationship(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                  is_expect, is_actual, start_offset, end_offset, line)
             VALUES
                 (20, 'class:actual:PlatformClock', 1, NULL, 'CLASS', 'PlatformClock',
                  'sample.PlatformClock', NULL, 0, 1, 500, 510, 50),
                 (21, 'class:expect:CommonClock', 1, NULL, 'CLASS', 'CommonClock',
                  'sample.CommonClock', NULL, 1, 0, 511, 520, 51);
             INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context, resolved_target_id,
                  start_offset, end_offset, line)
             VALUES
                 (70, 20, 21, 1, 'EXPECT_ACTUAL', 'NONE', 21, 500, 510, 50);",
        )
        .expect("compiler-backed expect/actual relationship");
}

fn seed_high_cardinality_outgoing_calls(fixture: &WorkspaceIndexFixture) {
    seed_outgoing_calls(fixture, 100..200);
}

fn seed_outgoing_calls(fixture: &WorkspaceIndexFixture, ids: std::ops::Range<i64>) {
    let mut connection = fixture.connection();
    let transaction = connection
        .transaction()
        .expect("outgoing calls seed transaction");
    for id in ids {
        let name = format!("target{id}");
        transaction
            .execute(
                "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, start_offset, end_offset, line)
                 VALUES (?, ?, 1, NULL, 'FUNCTION', ?, ?, ?, ?, ?, ?)",
                params![
                    id,
                    format!("callable:{name}"),
                    name,
                    format!("sample.{name}"),
                    format!("sample.{name}|-|||0"),
                    id * 10,
                    id * 10 + 5,
                    id
                ],
            )
            .expect("high-cardinality semantic symbol");
        transaction
            .execute(
                "INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
                 VALUES (?, 3, ?, 1, 'CALLS', 'CALL', ?, ?, ?, ?)",
                params![id, id, id, id * 10, id * 10 + 5, id],
            )
            .expect("high-cardinality semantic edge");
    }
    transaction.commit().expect("outgoing calls seed commit");
}

fn seed_discovery_name_collision(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
              start_offset, end_offset, line)
             VALUES
             (30, 'callable:other.semanticGraphOperation', 1, NULL, 'FUNCTION',
              'semanticGraphOperation', 'other.semanticGraphOperation',
              'other.semanticGraphOperation|-|||0', 480, 500, 48)",
            [],
        )
        .expect("discovery name collision");
}

fn seed_out_of_scope_repository_target(fixture: &WorkspaceIndexFixture) {
    fixture.insert_manifest_file(2, "other/src/test/kotlin/other", "OutsideScope.kt", true);
    let path = "other/src/test/kotlin/other/OutsideScope.kt";
    let content = std::fs::read(fixture.workspace_root().join(path)).expect("outside source");
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (2, 'other')",
            [],
        )
        .expect("outside package");
    connection
        .execute(
            "INSERT INTO file_metadata
             (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set)
             VALUES (2, 'OutsideScope.kt', 2, 'PROVEN_NAMED', NULL, 'idea.other.test', 'test')",
            [],
        )
        .expect("outside metadata");
    drop(connection);
    fixture.insert_project_evidence(2, "OutsideScope.kt", ".", ":other", "test");
    fixture.seed_progress("other", "COMPLETE", 1, 1);
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT INTO semantic_files
             (id, path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (2, ?, 'other', 'other.test', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("outside semantic file");
    connection
        .execute(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, start_offset, end_offset, line)
             VALUES
             (10, 'callable:outsideScope', 2, NULL, 'FUNCTION', 'outsideScope',
              'other.outsideScope', 'other.outsideScope|-|||0', 0, 20, 1)",
            [],
        )
        .expect("outside semantic symbol");
    connection
        .execute(
            "INSERT INTO semantic_edge_occurrences
             (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
             VALUES (8, 3, 10, 1, 'CALLS', 'CALL', 10, 195, 205, 19)",
            [],
        )
        .expect("cross-scope semantic edge");
}

#[test]
fn repository_discovery_paraphrases_preserve_evidence_derived_outcomes() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_discovery_name_collision(&fixture);

    let resolve = |id: &str, question: &str| {
        rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "repository/query",
                "params": {
                    "question": question,
                    "intent": "resolve",
                    "scope": {"language": "kotlin"},
                    "limits": {"depth": 1, "results": 10, "evidence": 2}
                }
            }),
        )
        .1
    };
    let outcome = |response: &serde_json::Value| {
        (
            response["result"]["status"].clone(),
            response["result"]["selectedIdentity"].clone(),
            response["result"]["candidates"]
                .as_array()
                .expect("repository candidates")
                .iter()
                .map(|candidate| candidate["canonicalKey"].clone())
                .collect::<Vec<_>>(),
        )
    };

    let baseline = resolve(
        "baseline",
        "Resolve the sample semanticGraphOperation declaration.",
    );
    let missing_wording = resolve(
        "missing-wording",
        "Resolve the sample semanticGraphOperation declaration even though \
         DefinitelyMissingRepositoryIntelligenceSymbol is absent.",
    );
    let ambiguity_wording = resolve(
        "ambiguity-wording",
        "Resolve the sample semanticGraphOperation declaration without choosing \
         a presentation.",
    );

    assert_eq!(baseline["result"]["status"], "ANSWERED", "{baseline:#}");
    assert_eq!(
        baseline["result"]["selectedIdentity"], "callable:semanticGraphOperation",
        "{baseline:#}"
    );
    assert_eq!(outcome(&missing_wording), outcome(&baseline));
    assert_eq!(outcome(&ambiguity_wording), outcome(&baseline));

    let bare_ambiguity = resolve("bare-ambiguity", "Resolve parse.");
    let paraphrased_ambiguity = resolve(
        "paraphrased-ambiguity",
        "Resolve parse without choosing a presentation.",
    );
    assert_eq!(
        bare_ambiguity["result"]["status"], "AMBIGUOUS",
        "{bare_ambiguity:#}"
    );
    assert_eq!(outcome(&paraphrased_ambiguity), outcome(&bare_ambiguity));

    let missing = resolve(
        "missing",
        "Resolve DefinitelyMissingRepositoryIntelligenceSymbol.",
    );
    assert_eq!(missing["result"]["status"], "EMPTY", "{missing:#}");
}

fn seed_included_build_app(fixture: &WorkspaceIndexFixture) {
    fixture.insert_manifest_file(2, "included/src/main/kotlin/included", "Included.kt", true);
    let path = "included/src/main/kotlin/included/Included.kt";
    let content = std::fs::read(fixture.workspace_root().join(path)).expect("included source");
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (2, 'included')",
            [],
        )
        .expect("included package");
    connection
        .execute(
            "INSERT INTO file_metadata
             (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set)
             VALUES (2, 'Included.kt', 2, 'PROVEN_NAMED', NULL, 'included.app.main', 'main')",
            [],
        )
        .expect("included metadata");
    drop(connection);
    fixture.insert_project_evidence(2, "Included.kt", "included", ":app", "main");
    fixture.seed_progress("included-app", "COMPLETE", 1, 1);
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_files
             (id, path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (2, ?, 'included', 'included.app.main', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("included semantic file");
}

fn seed_architecture_boundary_targets(fixture: &WorkspaceIndexFixture, count: usize) {
    for index in 0..count {
        let prefix_id = 10 + i64::try_from(index).expect("fixture index");
        let file_id = prefix_id;
        let symbol_id = 100 + i64::try_from(index).expect("fixture symbol index");
        let package = format!("boundary{index}");
        let build_root = format!("included{index}");
        let project_path = format!(":app{index}");
        let filename = format!("Boundary{index}.kt");
        let directory = format!("{build_root}/src/main/kotlin/{package}");
        let path = format!("{directory}/{filename}");
        fixture.insert_manifest_file(prefix_id, &directory, &filename, true);
        let content = std::fs::read(fixture.workspace_root().join(&path))
            .expect("architecture boundary source");
        let connection = fixture.connection();
        connection
            .execute(
                "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
                params![prefix_id, package],
            )
            .expect("architecture boundary package");
        connection
            .execute(
                "INSERT INTO file_metadata
                 (prefix_id, filename, package_fq_id, package_state,
                  package_unproven_reason, module_path, source_set)
                 VALUES (?, ?, ?, 'PROVEN_NAMED', NULL, ?, 'main')",
                params![
                    prefix_id,
                    filename,
                    prefix_id,
                    format!("legacy.boundary{index}.main")
                ],
            )
            .expect("architecture boundary metadata");
        drop(connection);
        fixture.insert_project_evidence(prefix_id, &filename, &build_root, &project_path, "main");
        fixture.seed_progress(&format!("boundary-{index}"), "COMPLETE", 1, 1);
        let connection = fixture.connection();
        connection
            .execute(
                "INSERT INTO semantic_files
                 (id, path, package_name, module_name, content_hash,
                  refresh_status, diagnostics_json)
                 VALUES (?, ?, ?, ?, ?, 'REFRESHED', '[]')",
                params![
                    file_id,
                    path,
                    package,
                    format!("legacy.boundary{index}.main"),
                    hex::encode(Sha256::digest(content))
                ],
            )
            .expect("architecture boundary semantic file");
        connection
            .execute(
                "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                  start_offset, end_offset, line)
                 VALUES (?, ?, ?, NULL, 'FUNCTION', ?, ?, ?, 0, 20, 1)",
                params![
                    symbol_id,
                    format!("callable:boundary{index}"),
                    file_id,
                    format!("boundary{index}"),
                    format!("boundary{index}.boundary{index}"),
                    format!("boundary{index}.boundary{index}|-|||0")
                ],
            )
            .expect("architecture boundary symbol");
        connection
            .execute(
                "INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context,
                  resolved_target_id, start_offset, end_offset, line)
                 VALUES (?, 3, ?, 1, 'REFERENCES', 'RETURN_TYPE', ?, ?, ?, ?)",
                params![
                    symbol_id,
                    symbol_id,
                    symbol_id,
                    symbol_id * 10,
                    symbol_id * 10 + 5,
                    symbol_id
                ],
            )
            .expect("architecture boundary edge");
    }
}

#[test]
fn repository_nodes_preserve_build_qualified_ownership() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_included_build_app(&fixture);
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
              start_offset, end_offset, line)
             VALUES
             (40, 'callable:includedOwnership', 2, NULL, 'FUNCTION',
              'includedOwnership', 'included.includedOwnership',
              'included.includedOwnership|-|||0', 0, 20, 1);
             INSERT INTO semantic_edge_occurrences
             (id, source_id, target_id, source_file_id, kind, context,
              resolved_target_id, start_offset, end_offset, line)
             VALUES
             (80, 3, 40, 1, 'REFERENCES', 'RETURN_TYPE', 40, 190, 195, 19);",
        )
        .expect("included-build semantic proof");
    std::fs::create_dir_all(workspace.join("included/app")).expect("included project directory");
    std::fs::write(
        workspace.join("included/app/build.gradle.kts"),
        "plugins { kotlin(\"jvm\") }\n",
    )
    .expect("included project build script");

    let resolve = |id: &str, canonical_key: &str| {
        rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "repository/query",
                "params": {
                    "question": "Resolve the exact compiler identity.",
                    "intent": "resolve",
                    "canonicalKey": canonical_key,
                    "scope": {"language": "kotlin"},
                    "limits": {"depth": 1, "results": 10, "evidence": 2}
                }
            }),
        )
        .1
    };
    let root = resolve("root-owner", "callable:semanticGraphOperation");
    let included = resolve("included-owner", "callable:includedOwnership");

    assert_eq!(
        root["result"]["nodes"][0]["gradleProjects"],
        serde_json::json!([".#:app"]),
        "{root:#}"
    );
    assert_eq!(
        root["result"]["nodes"][0]["sourceSets"],
        serde_json::json!([".#:app[main]"]),
        "{root:#}"
    );
    assert_eq!(
        included["result"]["nodes"][0]["gradleProjects"],
        serde_json::json!(["included#:app"]),
        "{included:#}"
    );
    assert_eq!(
        included["result"]["nodes"][0]["sourceSets"],
        serde_json::json!(["included#:app[main]"]),
        "{included:#}"
    );
    assert!(
        root["result"]["nodes"][0].get("module").is_none()
            && root["result"]["nodes"][0].get("sourceSet").is_none(),
        "{root:#}"
    );

    let discovery = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ownership-discovery",
            "method": "repository/query",
            "params": {
                "question": "Resolve includedOwnership.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1;
    assert_eq!(
        discovery["result"]["candidates"][0]["gradleProjects"],
        serde_json::json!(["included#:app"]),
        "{discovery:#}"
    );

    let architecture = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ownership-architecture",
            "method": "repository/query",
            "params": {
                "question": "Which Gradle ownership boundaries are crossed?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "MODULE_DEPENDENCIES"
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1;
    assert_eq!(
        architecture["result"]["findings"][0]["trigger"]["sourceModule"], ".#:app",
        "{architecture:#}"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["trigger"]["targetModule"], "included#:app",
        "{architecture:#}"
    );

    let context = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ownership-context",
            "method": "repository/query",
            "params": {
                "question": "Resolve includedOwnership context.",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["gradle"]},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1;
    assert_eq!(
        context["result"]["contextRelations"][0]["derivation"]["facts"]["gradleProject"],
        "included#:app",
        "{context:#}"
    );

    let compact = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Resolve includedOwnership exactly.",
            "--intent",
            "resolve",
            "--canonical-key",
            "callable:includedOwnership",
        ])
        .output()
        .expect("compact repository ownership");
    assert!(
        compact.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact.stdout),
        String::from_utf8_lossy(&compact.stderr)
    );
    let compact: serde_json::Value =
        toon_format::decode_default(String::from_utf8_lossy(&compact.stdout).trim())
            .expect("compact repository ownership TOON");
    assert_eq!(
        compact["result"]["identities"][0]["gradleProjects"],
        serde_json::json!(["included#:app"]),
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["identities"][0]["sourceSets"],
        serde_json::json!(["included#:app[main]"]),
        "{compact:#}"
    );
}

#[test]
fn repository_architecture_result_limits_are_truthful_and_bounded() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_architecture_boundary_targets(&fixture, 7);
    let request = |id: &str, results: usize| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "question": "Which Gradle ownership boundaries are crossed?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "MODULE_DEPENDENCIES"
                },
                "limits": {"depth": 1, "results": results, "evidence": 1}
            }
        })
    };
    let high = rpc(
        &home,
        &config_home,
        &workspace,
        request("all-boundaries", 10),
    )
    .1;
    let repeated = rpc(
        &home,
        &config_home,
        &workspace,
        request("all-boundaries-repeated", 10),
    )
    .1;
    let target_modules = |response: &serde_json::Value| {
        response["result"]["findings"]
            .as_array()
            .expect("architecture findings")
            .iter()
            .map(|finding| {
                finding["trigger"]["targetModule"]
                    .as_str()
                    .expect("target module")
                    .to_string()
            })
            .collect::<Vec<_>>()
    };
    assert_eq!(
        (
            high["result"]["findings"].as_array().map(Vec::len),
            high["result"]["truncated"].as_bool(),
            target_modules(&high),
            target_modules(&repeated),
        ),
        (
            Some(7),
            Some(false),
            (0..7)
                .map(|index| format!("included{index}#:app{index}"))
                .collect::<Vec<_>>(),
            target_modules(&high),
        ),
        "{high:#}"
    );

    let low = rpc(
        &home,
        &config_home,
        &workspace,
        request("bounded-boundaries", 3),
    )
    .1;
    assert_eq!(
        (
            low["result"]["findings"].as_array().map(Vec::len),
            low["result"]["truncated"].as_bool(),
        ),
        (Some(3), Some(true)),
        "{low:#}"
    );

    let compact = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Which Gradle ownership boundaries are crossed?",
            "--intent",
            "architecture",
            "--projection",
            "module-dependencies",
            "--results",
            "3",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact bounded architecture");
    assert!(
        compact.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact.stdout),
        String::from_utf8_lossy(&compact.stderr)
    );
    let compact: serde_json::Value =
        toon_format::decode_default(String::from_utf8_lossy(&compact.stdout).trim())
            .expect("compact bounded architecture TOON");
    assert_eq!(
        (
            compact["result"]["cardinality"]["findings"]["returned"].as_u64(),
            compact["result"]["cardinality"]["findings"]["completeness"].as_str(),
            compact["result"]["truncated"].as_bool(),
        ),
        (Some(3), Some("LOWER_BOUND"), Some(true)),
        "{compact:#}"
    );
}

#[test]
fn repository_traversal_is_scope_closed_and_snapshot_bound() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_out_of_scope_repository_target(&fixture);

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "scope-closed",
            "method": "repository/query",
            "params": {
                "question": "Show outgoing calls from semanticGraphOperation.",
                "intent": "outgoing_impact",
                "scope": {
                    "language": "kotlin",
                    "module": "app",
                    "sourceSet": "main",
                    "relations": ["CALLS"],
                    "maxDepth": 1
                },
                "limits": {"depth": 1, "results": 50, "evidence": 1}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["generation"], 41, "{response:#}");
    assert_eq!(
        response["result"]["inventoryGeneration"], 41,
        "{response:#}"
    );
    assert_eq!(response["result"]["graphGeneration"], 41, "{response:#}");
    assert_eq!(response["result"]["coverage"]["total"], 1, "{response:#}");
    assert!(
        response["result"]["nodes"]
            .as_array()
            .is_some_and(|nodes| nodes.iter().all(|node| {
                node["path"]
                    .as_str()
                    .is_some_and(|path| path.starts_with("src/main/kotlin/sample/"))
            })),
        "{response:#}"
    );
    assert!(
        response["result"]["edges"]
            .as_array()
            .is_some_and(|edges| edges.iter().all(|edge| {
                edge["sourceKey"] != "callable:outsideScope"
                    && edge["targetKey"] != "callable:outsideScope"
            })),
        "{response:#}"
    );
}

#[test]
fn repository_traversal_continuation_resumes_without_replay_or_drift() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_high_cardinality_outgoing_calls(&fixture);
    let question = "Show outgoing relationships from semanticGraphOperation.";

    let (first_status, first_page) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest::new(question),
    );
    assert!(first_status.success(), "{first_page:#}");
    assert_eq!(first_page["result"]["truncated"], true, "{first_page:#}");
    let first_continuation = first_page["result"]["continuation"]
        .as_str()
        .expect("truncated relationship page continuation")
        .to_string();

    for (label, changed_question, results, module, source_set, verbose) in [
        (
            "query",
            "Show outgoing CALLS relationships from semanticGraphOperation.",
            10,
            None,
            None,
            false,
        ),
        ("scope", question, 10, Some("app"), Some("main"), false),
        ("limit", question, 11, None, None, true),
    ] {
        let (status, response) = agent_repository_traversal_page(
            &home,
            &config_home,
            &workspace,
            AgentRepositoryTraversalRequest {
                question: changed_question,
                results,
                module,
                source_set,
                continuation: Some(&first_continuation),
                verbose,
            },
        );
        assert!(!status.success(), "{label}: {response:#}");
        assert_eq!(
            response["error"]["code"], "INVALID_REPOSITORY_CONTINUATION",
            "{label}: {response:#}"
        );
    }

    let mut forged = first_continuation.as_bytes().to_vec();
    let final_byte = forged.last_mut().expect("continuation signature");
    *final_byte = if *final_byte == b'0' { b'1' } else { b'0' };
    let forged = String::from_utf8(forged).expect("ASCII continuation");
    let (forged_status, forged_response) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest {
            continuation: Some(&forged),
            ..AgentRepositoryTraversalRequest::new(question)
        },
    );
    assert!(!forged_status.success(), "{forged_response:#}");
    assert_eq!(
        forged_response["error"]["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{forged_response:#}"
    );

    let source_path = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let source = std::fs::read(&source_path).expect("indexed Kotlin source");
    std::fs::write(&source_path, b"changed after traversal page")
        .expect("change coverage composition");
    let (changed_status, changed_response) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest {
            continuation: Some(&first_continuation),
            ..AgentRepositoryTraversalRequest::new(question)
        },
    );
    assert!(!changed_status.success(), "{changed_response:#}");
    assert_eq!(
        changed_response["error"]["code"], "STALE_REPOSITORY_CONTINUATION",
        "{changed_response:#}"
    );
    std::fs::write(&source_path, source).expect("restore indexed Kotlin source");

    let mut expected = std::collections::BTreeSet::from([
        (
            "callable:semanticGraphOperation".to_string(),
            "callable:buildSemanticGraphSnapshot".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
        (
            "callable:semanticGraphOperation".to_string(),
            "callable:cycleTarget".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
        (
            "callable:cycleTarget".to_string(),
            "callable:semanticGraphOperation".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
        (
            "callable:buildSemanticGraphSnapshot".to_string(),
            "callable:SemanticGraphSha256.parse".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
    ]);
    expected.extend((100..200).map(|id| {
        (
            "callable:semanticGraphOperation".to_string(),
            format!("callable:target{id}"),
            "CALLS".to_string(),
            "CALL".to_string(),
        )
    }));
    let mut seen = repository_relationship_identities(&first_page);
    let mut continuation = Some(first_continuation.clone());
    for _ in 1..=expected.len() {
        let Some(token) = continuation.take() else {
            break;
        };
        let (status, page) = agent_repository_traversal_page(
            &home,
            &config_home,
            &workspace,
            AgentRepositoryTraversalRequest {
                continuation: Some(&token),
                ..AgentRepositoryTraversalRequest::new(question)
            },
        );
        assert!(status.success(), "{page:#}");
        for identity in repository_relationship_identities(&page) {
            assert!(seen.insert(identity.clone()), "replayed {identity:?}");
        }
        continuation = page["result"]["continuation"].as_str().map(str::to_string);
        assert_eq!(
            page["result"]["truncated"].as_bool(),
            Some(continuation.is_some()),
            "{page:#}"
        );
    }
    assert!(continuation.is_none(), "traversal did not terminate");
    assert_eq!(seen, expected);

    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 42", [])
        .expect("advance graph generation");
    let (stale_status, stale_response) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest {
            continuation: Some(&first_continuation),
            ..AgentRepositoryTraversalRequest::new(question)
        },
    );
    assert!(!stale_status.success(), "{stale_response:#}");
    assert_eq!(
        stale_response["error"]["code"], "STALE_REPOSITORY_CONTINUATION",
        "{stale_response:#}"
    );

    let help = kast(&home, &config_home)
        .args(["agent", "repository", "--help"])
        .output()
        .expect("agent repository help");
    assert!(help.status.success());
    assert!(
        String::from_utf8_lossy(&help.stdout).contains("--continuation"),
        "{}",
        String::from_utf8_lossy(&help.stdout)
    );
}

#[test]
fn repository_traversal_continuation_is_admissible_at_high_cardinality() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_outgoing_calls(&fixture, 10_000..12_500);
    let mut continuation = serde_json::Value::Null;
    let mut seen = std::collections::BTreeSet::new();

    for page in 0..=6 {
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": format!("high-cardinality-{page}"),
                "method": "repository/query",
                "params": {
                    "question": "Show outgoing relationships from semanticGraphOperation.",
                    "intent": "outgoing_impact",
                    "scope": {
                        "language": "kotlin",
                        "relations": ["CALLS"],
                        "maxDepth": 1
                    },
                    "limits": {"depth": 1, "results": 500, "evidence": 1},
                    "continuation": continuation
                }
            }),
        );
        assert!(status.success(), "page {page}: {response:#}");
        for identity in response["result"]["edges"]
            .as_array()
            .expect("repository relationships")
            .iter()
            .map(|edge| {
                (
                    edge["sourceKey"].as_str().expect("edge source").to_string(),
                    edge["targetKey"].as_str().expect("edge target").to_string(),
                    edge["kind"].as_str().expect("edge kind").to_string(),
                    edge["context"].as_str().expect("edge context").to_string(),
                )
            })
        {
            assert!(seen.insert(identity.clone()), "replayed {identity:?}");
        }
        let Some(token) = response["result"]["continuation"].as_str() else {
            assert_eq!(seen.len(), 2_502, "{response:#}");
            return;
        };
        assert!(
            token.len() <= 16_384,
            "page {page} emitted an inadmissible {} byte continuation",
            token.len()
        );
        continuation = serde_json::Value::String(token.to_string());
    }

    panic!("high-cardinality traversal did not terminate");
}

#[test]
fn agent_repository_query_preserves_all_intent_contracts() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_high_cardinality_outgoing_calls(&fixture);
    let workspace_root = workspace.to_str().expect("workspace");
    let question = "Show outgoing relationships from semanticGraphOperation.";
    let args = [
        "agent",
        "repository",
        "--workspace-root",
        workspace_root,
        "--question",
        question,
        "--intent",
        "outgoing-impact",
        "--language",
        "kotlin",
        "--relation",
        "calls",
        "--max-depth",
        "1",
        "--depth",
        "1",
        "--results",
        "10",
        "--evidence",
        "1",
    ];

    let compact_output = kast(&home, &config_home)
        .args(args)
        .output()
        .expect("compact agent repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr),
    );
    let compact_raw = String::from_utf8(compact_output.stdout).expect("compact UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact repository TOON");
    assert_eq!(compact["method"], "agent/repository", "{compact:#}");
    assert_eq!(
        compact["result"]["type"], "KAST_AGENT_REPOSITORY_RESULT",
        "{compact:#}"
    );
    assert_eq!(compact["result"]["status"], "ANSWERED", "{compact:#}");
    assert_eq!(compact["result"]["generation"], 41, "{compact:#}");
    assert_eq!(
        compact["result"]["coverage"]["complete"], true,
        "{compact:#}"
    );
    assert_eq!(compact["result"]["bounds"]["results"], 10, "{compact:#}");
    assert_eq!(
        compact["result"]["cardinality"]["relationships"]["returned"], 10,
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["cardinality"]["relationships"]["completeness"], "LOWER_BOUND",
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["relationships"].as_array().map(Vec::len),
        Some(10),
        "{compact:#}"
    );
    assert_eq!(compact["result"]["truncated"], true, "{compact:#}");
    assert!(compact.get("request").is_none(), "{compact:#}");
    assert!(compact.get("response").is_none(), "{compact:#}");
    assert!(
        compact["result"]["relationships"][0]["sourceKey"].is_string()
            && compact["result"]["relationships"][0]["targetKey"].is_string()
            && compact["result"]["relationships"][0]["kind"] == "CALLS"
            && compact["result"]["relationships"][0]["firstOccurrence"]["path"].is_string(),
        "{compact:#}"
    );
    let compact_tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k_base")
        .encode_with_special_tokens(&compact_raw)
        .len();
    assert!(
        compact_tokens <= 1_500,
        "compact repository output used {compact_tokens} tokens:\n{compact_raw}"
    );

    let canonical_request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "repository/query",
        "params": {
            "question": question,
            "intent": "outgoing_impact",
            "scope": {
                "language": "kotlin",
                "relations": ["CALLS"],
                "maxDepth": 1
            },
            "limits": {"depth": 1, "results": 10, "evidence": 1}
        }
    });
    let canonical_output = rpc_output(&home, &config_home, &workspace, "json", &canonical_request);
    assert!(canonical_output.status.success());
    let canonical: serde_json::Value =
        serde_json::from_slice(&canonical_output.stdout).expect("canonical repository JSON");
    assert!(
        compact_raw.len() * 2 < canonical_output.stdout.len(),
        "compact={} canonical={}",
        compact_raw.len(),
        canonical_output.stdout.len()
    );

    let verbose_output = kast(&home, &config_home)
        .args(["--output", "json"])
        .args(args)
        .arg("--verbose")
        .output()
        .expect("verbose agent repository");
    assert!(
        verbose_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&verbose_output.stdout),
        String::from_utf8_lossy(&verbose_output.stderr),
    );
    let verbose: serde_json::Value =
        serde_json::from_slice(&verbose_output.stdout).expect("verbose repository JSON");
    assert_eq!(verbose["method"], "agent/repository", "{verbose:#}");
    assert_eq!(verbose["result"], canonical["result"], "{verbose:#}");

    let explain_output = kast(&home, &config_home)
        .args(["--output", "json"])
        .args(args)
        .arg("--explain")
        .output()
        .expect("explain agent repository");
    assert!(explain_output.status.success());
    let explain: serde_json::Value =
        serde_json::from_slice(&explain_output.stdout).expect("explain repository JSON");
    assert_eq!(explain["result"], canonical["result"], "{explain:#}");

    let count_output = kast(&home, &config_home)
        .args(["--output", "json"])
        .args(args)
        .arg("--count")
        .output()
        .expect("count agent repository");
    assert!(count_output.status.success());
    let count: serde_json::Value =
        serde_json::from_slice(&count_output.stdout).expect("count repository JSON");
    assert_eq!(
        count["result"]["type"], "KAST_AGENT_REPOSITORY_COUNT",
        "{count:#}"
    );
    assert_eq!(
        count["result"]["cardinality"]["relationships"]["completeness"], "LOWER_BOUND",
        "{count:#}"
    );
    let selected_output = kast(&home, &config_home)
        .args(["--output", "json"])
        .args(args)
        .args(["--fields", "relationships"])
        .output()
        .expect("selected outgoing repository");
    assert!(selected_output.status.success());
    let selected: serde_json::Value =
        serde_json::from_slice(&selected_output.stdout).expect("selected outgoing JSON");
    assert!(
        selected["result"]["relationships"].is_array(),
        "{selected:#}"
    );

    let path_args = [
        "agent",
        "repository",
        "--workspace-root",
        workspace_root,
        "--question",
        "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
        "--intent",
        "path",
        "--relation",
        "calls",
        "--direction",
        "outgoing",
        "--depth",
        "6",
        "--results",
        "10",
        "--evidence",
        "1",
    ];
    let path_output = kast(&home, &config_home)
        .args(["--output", "json"])
        .args(path_args)
        .output()
        .expect("path agent repository");
    assert!(
        path_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&path_output.stdout),
        String::from_utf8_lossy(&path_output.stderr)
    );
    let path: serde_json::Value =
        serde_json::from_slice(&path_output.stdout).expect("path repository JSON");
    assert_eq!(
        path["result"]["paths"]
            .as_array()
            .and_then(|paths| paths.last())
            .and_then(|path| path.get("canonicalKeys")),
        Some(&serde_json::json!([
            "callable:semanticGraphOperation",
            "callable:buildSemanticGraphSnapshot",
            "callable:SemanticGraphSha256.parse"
        ])),
        "{path:#}"
    );
    assert!(
        path["result"]["relationships"]
            .as_array()
            .is_some_and(|relationships| relationships.iter().all(|relationship| {
                relationship["firstOccurrence"]["path"].is_string()
                    || relationship["derivation"]["rule"].is_string()
            })),
        "{path:#}"
    );
    let selected_path_output = kast(&home, &config_home)
        .args(["--output", "json"])
        .args(path_args)
        .args(["--fields", "paths,relationships"])
        .output()
        .expect("selected path agent repository");
    assert!(selected_path_output.status.success());
    let selected_path: serde_json::Value = serde_json::from_slice(&selected_path_output.stdout)
        .expect("selected path repository JSON");
    assert_eq!(
        selected_path["result"]["type"], "KAST_AGENT_REPOSITORY_SELECTION",
        "{selected_path:#}"
    );
    assert!(
        selected_path["result"]["paths"].is_array(),
        "{selected_path:#}"
    );
    for view in ["--verbose", "--explain", "--count"] {
        let output = kast(&home, &config_home)
            .args(["--output", "json"])
            .args(path_args)
            .arg(view)
            .output()
            .expect("path repository view");
        assert!(
            output.status.success(),
            "view={view} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }

    let architecture_args = [
        "agent",
        "repository",
        "--workspace-root",
        workspace_root,
        "--question",
        "Which runtime call cycles cross package boundaries?",
        "--intent",
        "architecture",
        "--projection",
        "runtime-calls",
        "--metric",
        "scc",
        "--results",
        "10",
        "--evidence",
        "1",
    ];
    for view in [None, Some("--verbose"), Some("--explain")] {
        let mut command = kast(&home, &config_home);
        command.args(["--output", "json"]).args(architecture_args);
        if let Some(view) = view {
            command.arg(view);
        }
        let output = command.output().expect("architecture agent repository");
        assert!(
            output.status.success(),
            "view={view:?} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let architecture: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("architecture repository JSON");
        let finding = &architecture["result"]["findings"][0];
        assert!(finding["trigger"].is_object(), "{architecture:#}");
        assert!(
            finding["representativeSymbols"].is_array(),
            "{architecture:#}"
        );
        assert!(
            finding["supportingSubgraph"]["edges"].is_array(),
            "{architecture:#}"
        );
        assert!(
            finding["relationComposition"].is_object(),
            "{architecture:#}"
        );
    }
    for view in [["--fields", "findings"].as_slice(), ["--count"].as_slice()] {
        let output = kast(&home, &config_home)
            .args(["--output", "json"])
            .args(architecture_args)
            .args(view)
            .output()
            .expect("bounded architecture view");
        assert!(
            output.status.success(),
            "view={view:?} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }

    for (intent_args, selected_field) in [
        (
            vec![
                "--question",
                "Resolve SemanticGraphSha256.parse exactly.",
                "--intent",
                "resolve",
                "--canonical-key",
                "callable:SemanticGraphSha256.parse",
            ],
            "identities",
        ),
        (
            vec![
                "--question",
                "Show incoming callers of semanticGraphOperation.",
                "--intent",
                "incoming-impact",
                "--relation",
                "calls",
                "--depth",
                "1",
            ],
            "relationships",
        ),
        (
            vec![
                "--question",
                "Which repository files document SemanticGraphSha256?",
                "--intent",
                "context-relationship",
                "--source",
                "gradle",
                "--results",
                "10",
            ],
            "context",
        ),
    ] {
        for view in [None, Some("--verbose"), Some("--explain"), Some("--count")] {
            let mut command = kast(&home, &config_home);
            command
                .args([
                    "--output",
                    "json",
                    "agent",
                    "repository",
                    "--workspace-root",
                ])
                .arg(workspace_root)
                .args(&intent_args);
            if let Some(view) = view {
                command.arg(view);
            }
            let output = command.output().expect("remaining repository intent");
            assert!(
                output.status.success(),
                "view={view:?} stdout={} stderr={}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            );
            let response: serde_json::Value =
                serde_json::from_slice(&output.stdout).expect("remaining repository intent JSON");
            assert_eq!(response["method"], "agent/repository", "{response:#}");
        }
        let selected = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "repository",
                "--workspace-root",
            ])
            .arg(workspace_root)
            .args(&intent_args)
            .args(["--fields", selected_field])
            .output()
            .expect("selected repository intent");
        assert!(
            selected.status.success(),
            "field={selected_field} stdout={} stderr={}",
            String::from_utf8_lossy(&selected.stdout),
            String::from_utf8_lossy(&selected.stderr)
        );
    }

    assert!(
        !default_descriptor_dir(&home).exists(),
        "local repository query must not discover or start a daemon"
    );

    fixture
        .connection()
        .execute("DROP TABLE semantic_symbols", [])
        .expect("remove traversal authority");
    let missing_projection = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "repository",
            "--workspace-root",
            workspace_root,
            "--question",
            "Find architecture.",
            "--intent",
            "architecture",
        ])
        .output()
        .expect("missing architecture projection");
    assert!(!missing_projection.status.success());
    let missing_projection: serde_json::Value =
        serde_json::from_slice(&missing_projection.stdout).expect("missing projection JSON");
    assert_eq!(
        missing_projection["error"]["code"], "INVALID_REPOSITORY_QUERY",
        "{missing_projection:#}"
    );
    let invalid = kast(&home, &config_home)
        .args(args)
        .args(["--depth", "7"])
        .output()
        .expect("invalid repository bounds");
    assert_eq!(invalid.status.code(), Some(2));
}

#[test]
fn rpc_exposes_generation_pinned_complete_graph_coverage() {
    let (_temp, home, config_home, workspace, _fixture) = coverage_fixture();
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "coverage",
        "method": "graph/coverage",
        "params": {
            "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"}
        }
    });

    let (status, response) = rpc(&home, &config_home, &workspace, request);

    assert!(status.success(), "{response:#}");
    assert_eq!(response["id"], "coverage");
    assert_eq!(response["result"]["generation"], 41);
    assert_eq!(response["result"]["inventoryGeneration"], 41);
    assert_eq!(response["result"]["graphGeneration"], 41);
    assert_eq!(response["result"]["coverage"]["total"], 1);
    assert_eq!(response["result"]["coverage"]["indexed"], 1);
    assert_eq!(response["result"]["coverage"]["excluded"], 0);
    assert_eq!(response["result"]["coverage"]["failed"], 0);
    assert_eq!(response["result"]["coverage"]["stale"], 0);
    assert_eq!(response["result"]["coverage"]["complete"], true);
    assert_eq!(
        response["result"]["coverage"]["eligibleForCompleteNegative"],
        true
    );
    assert_eq!(response["result"]["appliedFilters"]["module"], "app");
    assert_eq!(response["result"]["appliedFilters"]["sourceSet"], "main");
}

#[test]
fn graph_coverage_continuation_rejects_tampering_and_drift() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(3);

    let mut legacy = graph_coverage_page_request("legacy", None, 2);
    legacy["params"]["afterPath"] = serde_json::json!("src/main/kotlin/sample/Source0000.kt");
    let (legacy_status, legacy_response) = rpc(&home, &config_home, &workspace, legacy);
    assert!(!legacy_status.success(), "{legacy_response:#}");
    assert_eq!(
        legacy_response["code"], "INVALID_GRAPH_COVERAGE_REQUEST",
        "{legacy_response:#}"
    );
    assert!(
        legacy_response["message"]
            .as_str()
            .is_some_and(|message| message.contains("unknown field")),
        "{legacy_response:#}"
    );

    let (first_status, first) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("first", None, 2),
    );
    assert!(first_status.success(), "{first:#}");
    let first_paths = first["result"]["files"]
        .as_array()
        .expect("first coverage files")
        .iter()
        .map(|file| file["path"].as_str().expect("coverage path").to_owned())
        .collect::<Vec<_>>();
    assert_eq!(
        first_paths,
        [
            "src/main/kotlin/sample/Source0000.kt",
            "src/main/kotlin/sample/Source0001.kt"
        ]
    );
    assert_eq!(first["result"]["truncated"], true);
    assert!(first["result"].get("nextAfterPath").is_none(), "{first:#}");
    let continuation = first["result"]["continuation"]
        .as_str()
        .expect("coverage continuation")
        .to_owned();

    let mut tampered = continuation.clone();
    let final_character = tampered.pop().expect("continuation character");
    tampered.push(if final_character == '0' { '1' } else { '0' });
    for (id, request) in [
        (
            "tampered",
            graph_coverage_page_request("tampered", Some(&tampered), 2),
        ),
        (
            "limit-mismatch",
            graph_coverage_page_request("limit-mismatch", Some(&continuation), 1),
        ),
    ] {
        let (status, response) = rpc(&home, &config_home, &workspace, request);
        assert!(!status.success(), "{id}: {response:#}");
        assert_eq!(
            response["code"], "INVALID_GRAPH_COVERAGE_CONTINUATION",
            "{id}: {response:#}"
        );
    }
    let mut scope_mismatch = graph_coverage_page_request("scope-mismatch", Some(&continuation), 2);
    scope_mismatch["params"]["scope"]["module"] = serde_json::json!(".#:app");
    let (scope_status, scope_response) = rpc(&home, &config_home, &workspace, scope_mismatch);
    assert!(!scope_status.success(), "{scope_response:#}");
    assert_eq!(
        scope_response["code"], "INVALID_GRAPH_COVERAGE_CONTINUATION",
        "{scope_response:#}"
    );

    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 42", [])
        .expect("advance graph generation");
    let (generation_status, generation_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("generation-drift", Some(&continuation), 2),
    );
    assert!(!generation_status.success(), "{generation_response:#}");
    assert_eq!(
        generation_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{generation_response:#}"
    );
    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 41", [])
        .expect("restore graph generation");

    let source_path = workspace.join("src/main/kotlin/sample/Source0002.kt");
    let source_content = std::fs::read(&source_path).expect("original source");
    std::fs::write(&source_path, "package sample\nclass Changed\n").expect("changed source");
    let (composition_status, composition_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("composition-drift", Some(&continuation), 2),
    );
    assert!(!composition_status.success(), "{composition_response:#}");
    assert_eq!(
        composition_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{composition_response:#}"
    );
    std::fs::write(&source_path, source_content).expect("restore source");

    std::fs::create_dir_all(workspace.join("included")).expect("included build");
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path)
                 SELECT prefix_id, filename, 'included', project_path
                 FROM file_gradle_projects
                 WHERE build_root = '.' AND project_path = ':app';
             INSERT INTO file_gradle_source_sets(
                 prefix_id, filename, build_root, project_path, source_set_name
             )
                 SELECT prefix_id, filename, 'included', project_path, source_set_name
                 FROM file_gradle_source_sets
                 WHERE build_root = '.' AND project_path = ':app';",
        )
        .expect("add ambiguous resolved scope");
    let (ambiguous_status, ambiguous_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("ambiguous-scope-drift", Some(&continuation), 2),
    );
    assert!(!ambiguous_status.success(), "{ambiguous_response:#}");
    assert_eq!(
        ambiguous_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{ambiguous_response:#}"
    );
    fixture
        .connection()
        .execute_batch(
            "DELETE FROM file_gradle_source_sets
                 WHERE build_root = 'included' AND project_path = ':app';
             DELETE FROM file_gradle_projects
                 WHERE build_root = 'included' AND project_path = ':app';",
        )
        .expect("remove ambiguous resolved scope");

    fixture
        .connection()
        .execute_batch(
            "PRAGMA defer_foreign_keys = ON;
             BEGIN;
             UPDATE file_gradle_projects
                 SET build_root = 'included'
                 WHERE project_path = ':app';
             UPDATE file_gradle_source_sets
                 SET build_root = 'included'
                 WHERE project_path = ':app';
             COMMIT;",
        )
        .expect("move resolved scope");
    let (resolved_status, resolved_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("resolved-scope-drift", Some(&continuation), 2),
    );
    assert!(!resolved_status.success(), "{resolved_response:#}");
    assert_eq!(
        resolved_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{resolved_response:#}"
    );
    fixture
        .connection()
        .execute_batch(
            "PRAGMA defer_foreign_keys = ON;
             BEGIN;
             UPDATE file_gradle_projects
                 SET build_root = '.'
                 WHERE project_path = ':app';
             UPDATE file_gradle_source_sets
                 SET build_root = '.'
                 WHERE project_path = ':app';
             COMMIT;",
        )
        .expect("restore resolved scope");

    let (second_status, second) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("second", Some(&continuation), 2),
    );
    assert!(second_status.success(), "{second:#}");
    let second_paths = second["result"]["files"]
        .as_array()
        .expect("second coverage files")
        .iter()
        .map(|file| file["path"].as_str().expect("coverage path").to_owned())
        .collect::<Vec<_>>();
    assert_eq!(
        second_paths,
        ["src/main/kotlin/sample/Source0002.kt"],
        "{second:#}"
    );
    assert_eq!(
        first_paths
            .into_iter()
            .chain(second_paths)
            .collect::<Vec<_>>(),
        [
            "src/main/kotlin/sample/Source0000.kt",
            "src/main/kotlin/sample/Source0001.kt",
            "src/main/kotlin/sample/Source0002.kt"
        ]
    );
    assert_eq!(second["result"]["truncated"], false);
    assert_eq!(second["result"]["continuation"], serde_json::Value::Null);
    assert!(
        second["result"].get("nextAfterPath").is_none(),
        "{second:#}"
    );
}

#[cfg(unix)]
#[test]
fn repository_query_enforces_routed_root_authority() {
    use std::os::unix::fs::symlink;

    let (temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    std::fs::create_dir_all(workspace.join("docs")).expect("context fixture directory");
    let outside_document = temp.path().join("outside.md");
    std::fs::write(
        &outside_document,
        "# Outside\n\nSemanticGraphSha256 must never be read through the workspace.\n",
    )
    .expect("outside context document");
    let linked_document = workspace.join("docs/outside.md");
    symlink(&outside_document, &linked_document).expect("outside context symlink");

    let request = || {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "root-authority",
            "method": "repository/query",
            "params": {
                "question": "Which document explains SemanticGraphSha256?",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["markdown"]},
                "limits": {"depth": 1, "results": 10, "evidence": 1}
            }
        })
    };

    let mut conflicting_request = request();
    conflicting_request["params"]["workspaceRoot"] = serde_json::json!(temp.path());
    let (conflict_status, conflict) = rpc(&home, &config_home, &workspace, conflicting_request);
    let mut missing_request = request();
    missing_request["params"]["workspaceRoot"] =
        serde_json::json!(temp.path().join("missing-workspace"));
    let (missing_status, missing) = rpc(&home, &config_home, &workspace, missing_request);
    let (escape_status, escape) = rpc(&home, &config_home, &workspace, request());
    let toon_escape_output = rpc_output(&home, &config_home, &workspace, "toon", &request());
    let toon_escape_raw =
        String::from_utf8(toon_escape_output.stdout).expect("context error TOON UTF-8");
    let toon_escape: serde_json::Value =
        toon_format::decode_default(toon_escape_raw.trim()).expect("context error TOON");

    std::fs::remove_file(&linked_document).expect("remove outside context symlink");
    let outside_directory = temp.path().join("outside-docs");
    std::fs::create_dir(&outside_directory).expect("outside context directory");
    std::fs::write(
        outside_directory.join("outside.md"),
        "# Outside directory\n\nSemanticGraphSha256 remains outside the workspace.\n",
    )
    .expect("outside directory context document");
    let linked_directory = workspace.join("docs/outside-directory");
    symlink(&outside_directory, &linked_directory).expect("outside context directory symlink");
    let (directory_escape_status, directory_escape) =
        rpc(&home, &config_home, &workspace, request());

    std::fs::remove_file(&linked_directory).expect("remove outside context directory symlink");
    std::fs::write(
        workspace.join("docs/inside.md"),
        "# Inside\n\nSemanticGraphSha256 is compiler-backed repository evidence.\n",
    )
    .expect("inside context document");
    let mut valid_request = request();
    valid_request["params"]["workspaceRoot"] = serde_json::json!(workspace);
    let (valid_status, valid) = rpc(&home, &config_home, &workspace, valid_request);

    assert_eq!(
        serde_json::json!({
            "rootConflict": {
                "success": conflict_status.success(),
                "ok": conflict["ok"],
                "code": conflict["code"]
            },
            "missingBodyRoot": {
                "success": missing_status.success(),
                "ok": missing["ok"],
                "code": missing["code"]
            },
            "symlinkEscape": {
                "success": escape_status.success(),
                "ok": escape["ok"],
                "code": escape["code"]
            },
            "toonSymlinkEscape": {
                "success": toon_escape_output.status.success(),
                "ok": toon_escape["ok"],
                "code": toon_escape["code"],
                "actionable": toon_escape["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("remove the symlink")),
                "schemaVersionMatchesJson": toon_escape["schemaVersion"] == escape["schemaVersion"]
            },
            "directorySymlinkEscape": {
                "success": directory_escape_status.success(),
                "ok": directory_escape["ok"],
                "code": directory_escape["code"]
            },
            "validExactRoot": {
                "success": valid_status.success(),
                "status": valid["result"]["status"],
                "canonicalRoot": valid["result"]["workspaceIdentity"]["canonicalRoot"]
            }
        }),
        serde_json::json!({
            "rootConflict": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_WORKSPACE_ROOT_MISMATCH"
            },
            "missingBodyRoot": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_WORKSPACE_ROOT_MISMATCH"
            },
            "symlinkEscape": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE"
            },
            "toonSymlinkEscape": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE",
                "actionable": true,
                "schemaVersionMatchesJson": true
            },
            "directorySymlinkEscape": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE"
            },
            "validExactRoot": {
                "success": true,
                "status": "ANSWERED",
                "canonicalRoot": workspace
            }
        })
    );
}

#[test]
fn repository_scope_is_strict_and_build_qualified() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let coverage_request = |id: &str, module: &str| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "graph/coverage",
            "params": {
                "workspaceRoot": workspace,
                "scope": {
                    "language": "kotlin",
                    "module": module,
                    "sourceSet": "main"
                }
            }
        })
    };

    let (unique_status, unique) = rpc(
        &home,
        &config_home,
        &workspace,
        coverage_request("unique-short", "app"),
    );
    assert!(unique_status.success(), "{unique:#}");
    assert_eq!(unique["result"]["coverage"]["total"], 1);

    seed_included_build_app(&fixture);
    for (module, expected) in [(".#:app", ".#:app"), ("included#:app", "included#:app")] {
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            coverage_request(module, module),
        );
        assert!(status.success(), "{module}: {response:#}");
        assert_eq!(response["result"]["coverage"]["total"], 1);
        assert_eq!(
            response["result"]["coverage"]["modules"][0]["name"],
            expected
        );
    }

    fixture
        .connection()
        .execute("DROP TABLE semantic_files", [])
        .expect("remove semantic execution authority");

    let repository_request = |id: &str| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "workspaceRoot": workspace,
                "question": "Resolve semanticGraphOperation.",
                "intent": "resolve",
                "scope": {
                    "language": "kotlin",
                    "module": "app",
                    "sourceSet": "main"
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };
    let (ambiguous_status, ambiguous) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_request("ambiguous"),
    );
    assert!(!ambiguous_status.success(), "{ambiguous:#}");
    assert_eq!(
        ambiguous["code"], "AMBIGUOUS_REPOSITORY_SCOPE",
        "{ambiguous:#}"
    );
    let message = ambiguous["message"].as_str().expect("ambiguity message");
    let root_candidate = message.find(".#:app").expect("root candidate");
    let included_candidate = message
        .find("included#:app")
        .expect("included-build candidate");
    assert!(root_candidate < included_candidate, "{message}");

    let mut unknown_request = repository_request("unknown-request");
    unknown_request["params"]["queston"] = serde_json::json!("typo");
    let mut unknown_envelope = repository_request("unknown-envelope");
    unknown_envelope["trace"] = serde_json::json!(true);
    let mut unknown_scope = repository_request("unknown-scope");
    unknown_scope["params"]["scope"]["moduel"] = serde_json::json!("app");
    let mut unknown_limits = repository_request("unknown-limits");
    unknown_limits["params"]["limits"]["reslts"] = serde_json::json!(10);
    let mut unknown_coverage = coverage_request("unknown-coverage", ".#:app");
    unknown_coverage["params"]["afterPth"] = serde_json::json!("Source.kt");
    let mut unknown_coverage_scope = coverage_request("unknown-coverage-scope", ".#:app");
    unknown_coverage_scope["params"]["scope"]["relations"] = serde_json::json!(["CALLS"]);

    for (request, expected_code) in [
        (unknown_request, "INVALID_REPOSITORY_QUERY"),
        (unknown_envelope, "INVALID_REPOSITORY_QUERY"),
        (unknown_scope, "INVALID_REPOSITORY_QUERY"),
        (unknown_limits, "INVALID_REPOSITORY_QUERY"),
        (unknown_coverage, "INVALID_GRAPH_COVERAGE_REQUEST"),
        (unknown_coverage_scope, "INVALID_GRAPH_COVERAGE_REQUEST"),
    ] {
        let (status, response) = rpc(&home, &config_home, &workspace, request);
        assert!(!status.success(), "{response:#}");
        assert_eq!(response["code"], expected_code, "{response:#}");
        assert!(
            response["message"]
                .as_str()
                .is_some_and(|message| message.contains("unknown field")),
            "{response:#}"
        );
    }
}

#[test]
fn repository_query_rejects_intent_irrelevant_fields_before_execution() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    fixture
        .connection()
        .execute("DROP TABLE semantic_files", [])
        .expect("remove semantic execution authority");
    let request = |id: &str, intent: &str| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "question": "Inspect the repository contract.",
                "intent": intent,
                "canonicalKey": null,
                "scope": {
                    "language": "kotlin",
                    "relations": [],
                    "direction": null,
                    "maxDepth": null,
                    "projection": null,
                    "metric": null,
                    "sources": []
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };
    let cases = [
        (
            "canonical-key",
            "path",
            "/params/canonicalKey",
            serde_json::json!("callable:sample.target"),
            "canonicalKey",
        ),
        (
            "relations",
            "resolve",
            "/params/scope/relations",
            serde_json::json!(["CALLS"]),
            "relations",
        ),
        (
            "max-depth",
            "resolve",
            "/params/scope/maxDepth",
            serde_json::json!(1),
            "maxDepth",
        ),
        (
            "direction",
            "resolve",
            "/params/scope/direction",
            serde_json::json!("OUTGOING"),
            "direction",
        ),
        (
            "projection",
            "path",
            "/params/scope/projection",
            serde_json::json!("RUNTIME_CALLS"),
            "projection",
        ),
        (
            "metric",
            "path",
            "/params/scope/metric",
            serde_json::json!("BRIDGES"),
            "metric",
        ),
        (
            "sources",
            "path",
            "/params/scope/sources",
            serde_json::json!(["markdown"]),
            "sources",
        ),
        (
            "depth-bound",
            "path",
            "/params/scope/maxDepth",
            serde_json::json!(2),
            "maxDepth",
        ),
    ];

    for (id, intent, pointer, value, expected_field) in cases {
        let mut invalid = request(id, intent);
        *invalid.pointer_mut(pointer).expect("contract field") = value;
        let (status, response) = rpc(&home, &config_home, &workspace, invalid);

        assert!(!status.success(), "{id}: {response:#}");
        assert_eq!(
            response["code"], "INVALID_REPOSITORY_QUERY",
            "{id}: {response:#}"
        );
        assert!(
            response["message"]
                .as_str()
                .is_some_and(|message| message.contains(expected_field)),
            "{id}: {response:#}"
        );
    }

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        request("architecture-projection", "architecture"),
    );
    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "INVALID_REPOSITORY_QUERY", "{response:#}");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("projection")),
        "{response:#}"
    );
}

#[test]
fn repository_negative_answers_follow_coverage_state() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    let request = |scope: serde_json::Value| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "negative",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": scope,
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };

    let (status, complete) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{complete:#}");
    assert_eq!(complete["result"]["status"], "EMPTY");
    assert_eq!(complete["result"]["coverage"]["complete"], true);

    std::fs::write(
        workspace.join("src/main/kotlin/sample/Source0000.kt"),
        "package sample\nclass Changed\n",
    )
    .expect("stale source");
    let (status, stale) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{stale:#}");
    assert_eq!(stale["result"]["status"], "QUALIFIED_EMPTY");
    assert_eq!(stale["result"]["coverage"]["stale"], 1);
    assert!(stale["result"]["qualification"].is_string());

    fixture
        .connection()
        .execute("DELETE FROM semantic_files", [])
        .expect("remove semantic graph file");
    let (status, failed) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{failed:#}");
    assert_eq!(failed["result"]["coverage"]["failed"], 1);
    assert_eq!(failed["result"]["coverage"]["complete"], false);
    let (_, failed_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "failed-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        failed_coverage["result"]["files"][0]["diagnostics"][0]["code"],
        "SEMANTIC_GRAPH_MISSING"
    );

    fixture
        .connection()
        .execute("DELETE FROM file_metadata", [])
        .expect("remove compilation ownership evidence");
    let (status, excluded) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{excluded:#}");
    assert_eq!(excluded["result"]["coverage"]["excluded"], 1);
    assert_eq!(excluded["result"]["coverage"]["failed"], 0);
    assert_eq!(excluded["result"]["coverage"]["eligibilityProven"], false);
    assert_eq!(excluded["result"]["coverage"]["complete"], false);
    let (_, excluded_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "excluded-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        excluded_coverage["result"]["files"][0]["reasonCode"],
        "SOURCE_INDEX_METADATA_UNAVAILABLE"
    );
}

#[test]
fn repository_path_preserves_overloaded_target_ambiguity() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, start_offset, end_offset, line)
             VALUES
                 (10, 'callable:resolveTarget.Int', 1, NULL, 'FUNCTION', 'resolveTarget',
                  'sample.resolveTarget', 'sample.resolveTarget|-||kotlin.Int|0', 500, 510, 50),
                 (11, 'callable:resolveTarget.String', 1, NULL, 'FUNCTION', 'resolveTarget',
                  'sample.resolveTarget', 'sample.resolveTarget|-||kotlin.String|0', 511, 520, 51);
             INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
             VALUES
                 (80, 3, 10, 1, 'CALLS', 'CALL', 10, 500, 510, 50),
                 (81, 3, 11, 1, 'CALLS', 'CALL', 11, 511, 520, 51);",
        )
        .expect("direct calls to both overloaded targets");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "overloaded-path-target",
            "method": "repository/query",
            "params": {
                "question": "Trace outgoing CALLS from semanticGraphOperation to resolveTarget.",
                "intent": "path",
                "scope": {
                    "language": "kotlin",
                    "relations": ["CALLS"],
                    "direction": "OUTGOING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["status"], "AMBIGUOUS", "{response:#}");
    assert_eq!(
        response["result"]["nodes"].as_array().map(|nodes| {
            nodes
                .iter()
                .map(|node| node["canonicalKey"].as_str().expect("canonical key"))
                .collect::<Vec<_>>()
        }),
        Some(vec![
            "callable:resolveTarget.Int",
            "callable:resolveTarget.String"
        ]),
        "{response:#}"
    );
    assert_eq!(
        response["result"]["paths"].as_array().map(Vec::len),
        Some(0),
        "{response:#}"
    );
    assert_eq!(
        response["result"]["edges"].as_array().map(Vec::len),
        Some(0),
        "{response:#}"
    );
}

#[test]
fn repository_context_ambiguity_preserves_exact_candidates() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                  start_offset, end_offset, line)
             VALUES
                 (10, 'callable:z.parse', 1, NULL, 'FUNCTION', 'parse', 'z.parse',
                  'z.parse|-||kotlin.String|0', 500, 510, 50)",
            [],
        )
        .expect("third colliding context target");
    let question = "Resolve parse context.";
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "ambiguous-context-target",
        "method": "repository/query",
        "params": {
            "question": question,
            "intent": "context_relationship",
            "scope": {"language": "kotlin", "sources": ["markdown"]},
            "limits": {"depth": 6, "results": 2, "evidence": 1}
        }
    });

    let (status, canonical) = rpc(&home, &config_home, &workspace, request);
    assert!(status.success(), "{canonical:#}");
    assert_eq!(canonical["result"]["status"], "AMBIGUOUS", "{canonical:#}");
    let canonical_ambiguity = &canonical["result"]["ambiguousReferences"][0];
    assert_eq!(canonical_ambiguity["reference"], "parse", "{canonical:#}");
    assert_eq!(
        canonical_ambiguity["candidates"]
            .as_array()
            .map(|candidates| {
                candidates
                    .iter()
                    .map(|candidate| candidate["canonicalKey"].as_str().expect("canonical key"))
                    .collect::<Vec<_>>()
            }),
        Some(vec![
            "callable:SemanticGraphSha256.parse",
            "callable:other.parse"
        ]),
        "{canonical:#}"
    );
    assert_eq!(canonical_ambiguity["truncated"], true, "{canonical:#}");
    assert_eq!(canonical["result"]["truncated"], true, "{canonical:#}");

    let compact_output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "2",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact ambiguous context repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr)
    );
    let compact_raw = String::from_utf8(compact_output.stdout).expect("compact context UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact context TOON");
    assert_eq!(compact["result"]["status"], "AMBIGUOUS", "{compact:#}");
    assert_eq!(
        compact["result"]["context"]["ambiguousReferences"][0]["candidates"]
            .as_array()
            .map(|candidates| {
                candidates
                    .iter()
                    .map(|candidate| candidate["canonicalKey"].as_str().expect("canonical key"))
                    .collect::<Vec<_>>()
            }),
        Some(vec![
            "callable:SemanticGraphSha256.parse",
            "callable:other.parse"
        ]),
        "{compact:#}"
    );
    assert_eq!(compact["result"]["truncated"], true, "{compact:#}");
}

#[test]
fn repository_terminal_context_resolution_skips_content_reads() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    std::fs::create_dir_all(workspace.join("docs")).expect("context fixture directory");
    std::fs::write(workspace.join("docs/unrelated.md"), [0xff, 0xfe])
        .expect("malformed context fixture");

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Resolve parse context.",
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
        ])
        .output()
        .expect("terminal context repository");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let raw = String::from_utf8(output.stdout).expect("terminal context UTF-8");
    let response: serde_json::Value =
        toon_format::decode_default(raw.trim()).expect("terminal context TOON");
    assert_eq!(
        serde_json::json!({
            "status": response["result"]["status"],
            "candidates": response["result"]["context"]["ambiguousReferences"][0]["candidates"]
                .as_array()
                .expect("ambiguity candidates")
                .iter()
                .map(|candidate| candidate["canonicalKey"].clone())
                .collect::<Vec<_>>()
        }),
        serde_json::json!({
            "status": "AMBIGUOUS",
            "candidates": [
                "callable:SemanticGraphSha256.parse",
                "callable:other.parse"
            ]
        }),
        "{response:#}"
    );
}

#[test]
fn repository_context_empty_preserves_unresolved_references() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let question = "Resolve MissingContextSymbol context.";

    let compact_output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact empty context repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr)
    );
    let compact_raw =
        String::from_utf8(compact_output.stdout).expect("compact empty context UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact empty context TOON");
    assert_eq!(
        serde_json::json!({
            "status": compact["result"]["status"],
            "unresolvedReferences": compact["result"]["context"]["unresolvedReferences"]
        }),
        serde_json::json!({
            "status": "EMPTY",
            "unresolvedReferences": ["MissingContextSymbol"]
        }),
        "{compact:#}"
    );

    let selected_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
            "--fields",
            "context",
        ])
        .output()
        .expect("selected empty context repository");
    assert!(
        selected_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&selected_output.stdout),
        String::from_utf8_lossy(&selected_output.stderr)
    );
    let selected: serde_json::Value =
        serde_json::from_slice(&selected_output.stdout).expect("selected empty context JSON");
    assert_eq!(
        selected["result"]["context"]["unresolvedReferences"],
        serde_json::json!(["MissingContextSymbol"]),
        "{selected:#}"
    );
}

#[test]
fn repository_relationship_preserves_expect_actual_edges() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_expect_actual_relationship(&fixture);

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "expect-actual-relationship",
            "method": "repository/query",
            "params": {
                "question": "Show outgoing EXPECT_ACTUAL relationships from PlatformClock.",
                "intent": "outgoing_impact",
                "scope": {
                    "language": "kotlin",
                    "relations": ["EXPECT_ACTUAL"],
                    "maxDepth": 1
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(
        serde_json::json!({
            "status": response["result"]["status"],
            "nodes": response["result"]["nodes"]
                .as_array()
                .expect("relationship nodes")
                .iter()
                .map(|node| node["canonicalKey"].clone())
                .collect::<Vec<_>>(),
            "relationships": response["result"]["edges"]
                .as_array()
                .expect("relationship edges")
                .iter()
                .map(|edge| serde_json::json!({
                    "sourceKey": edge["sourceKey"],
                    "targetKey": edge["targetKey"],
                    "kind": edge["kind"],
                    "evidenceClass": edge["evidenceClass"]
                }))
                .collect::<Vec<_>>()
        }),
        serde_json::json!({
            "status": "ANSWERED",
            "nodes": [
                "class:actual:PlatformClock",
                "class:expect:CommonClock"
            ],
            "relationships": [{
                "sourceKey": "class:actual:PlatformClock",
                "targetKey": "class:expect:CommonClock",
                "kind": "EXPECT_ACTUAL",
                "evidenceClass": "compiler"
            }]
        }),
        "{response:#}"
    );
}

#[test]
fn agent_repository_exposes_expect_actual_relation() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_expect_actual_relationship(&fixture);
    let workspace_root = workspace.to_str().expect("workspace");

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace_root,
            "--question",
            "Show outgoing EXPECT_ACTUAL relationships from PlatformClock.",
            "--intent",
            "outgoing-impact",
            "--relation",
            "expect-actual",
            "--max-depth",
            "1",
            "--depth",
            "1",
            "--results",
            "10",
            "--evidence",
            "2",
        ])
        .output()
        .expect("agent repository expect/actual relation");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let compact_raw = String::from_utf8(output.stdout).expect("compact UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact repository TOON");

    let help = kast(&home, &config_home)
        .args(["agent", "repository", "--help"])
        .output()
        .expect("agent repository help");
    let help_stdout = String::from_utf8(help.stdout).expect("help UTF-8");

    assert_eq!(
        serde_json::json!({
            "helpAdvertisesRelation": help.status.success()
                && help_stdout.contains("expect-actual"),
            "status": compact["result"]["status"],
            "relationships": compact["result"]["relationships"]
                .as_array()
                .expect("compact relationships")
                .iter()
                .map(|relationship| serde_json::json!({
                    "sourceKey": relationship["sourceKey"],
                    "targetKey": relationship["targetKey"],
                    "kind": relationship["kind"],
                    "evidenceClass": relationship["evidenceClass"]
                }))
                .collect::<Vec<_>>()
        }),
        serde_json::json!({
            "helpAdvertisesRelation": true,
            "status": "ANSWERED",
            "relationships": [{
                "sourceKey": "class:actual:PlatformClock",
                "targetKey": "class:expect:CommonClock",
                "kind": "EXPECT_ACTUAL",
                "evidenceClass": "compiler"
            }]
        }),
        "help={help_stdout}\ncompact={compact:#}"
    );
}

#[test]
fn repository_paths_carry_exact_identity_occurrences_and_derivations() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);

    let (_, exact) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "exact",
            "method": "repository/query",
            "params": {
                "question": "Resolve SemanticGraphSha256.parse exactly.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(exact["result"]["status"], "ANSWERED", "{exact:#}");
    assert_eq!(exact["result"]["nodes"][0]["name"], "parse");
    assert_eq!(
        exact["result"]["nodes"][0]["ownerName"],
        "SemanticGraphSha256"
    );
    assert_eq!(
        exact["result"]["nodes"][0]["parameterTypes"],
        serde_json::json!(["kotlin.String"])
    );

    let (_, path) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "path",
            "method": "repository/query",
            "params": {
                "question": "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
                "intent": "path",
                "scope": {
                    "language": "kotlin",
                    "relations": ["CALLS"],
                    "direction": "OUTGOING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 1}
            }
        }),
    );
    assert_eq!(path["result"]["status"], "ANSWERED", "{path:#}");
    assert!(path["result"]["paths"].as_array().is_some_and(|paths| {
        paths.iter().any(|path| {
            path["nodes"].as_array().is_some_and(|nodes| {
                nodes
                    .first()
                    .is_some_and(|node| node["name"] == "semanticGraphOperation")
                    && nodes.last().is_some_and(|node| node["name"] == "parse")
            })
        })
    }));
    assert!(path["result"]["edges"].as_array().is_some_and(|edges| {
        edges.iter().all(|edge| {
            edge["occurrences"]
                .as_array()
                .is_some_and(|values| !values.is_empty())
                || edge["derivation"].is_object()
        })
    }));
    assert!(path["result"]["edges"].as_array().is_some_and(|edges| {
        edges.iter().any(|edge| {
            edge["evidenceClass"] == "compiler"
                && edge["derivation"]["rule"] == "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER"
        })
    }));
    let derived_edge = path["result"]["edges"]
        .as_array()
        .and_then(|edges| {
            edges
                .iter()
                .find(|edge| edge["derivation"]["rule"] == "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER")
        })
        .expect("derived path edge");
    assert_eq!(derived_edge["occurrenceCount"], 3);
    assert_eq!(derived_edge["evidenceTruncated"], true);
    let continuation = derived_edge["evidenceContinuation"].clone();

    let source_path = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let source = std::fs::read(&source_path).expect("indexed Kotlin source");
    std::fs::write(&source_path, b"changed after evidence page")
        .expect("change coverage composition");
    let (changed_status, changed) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("changed-evidence", continuation.clone(), 1),
    );
    assert!(!changed_status.success(), "{changed:#}");
    assert_eq!(
        changed["code"], "STALE_REPOSITORY_CONTINUATION",
        "{changed:#}"
    );
    std::fs::write(&source_path, source).expect("restore indexed Kotlin source");

    let (mismatched_status, mismatched) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("mismatched-evidence", continuation.clone(), 10),
    );
    assert!(!mismatched_status.success(), "{mismatched:#}");
    assert_eq!(
        mismatched["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{mismatched:#}"
    );
    assert!(continuation.is_string());

    let (_, remaining_evidence) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("remaining-evidence", continuation.clone(), 1),
    );
    assert_eq!(
        remaining_evidence["result"]["edges"][0]["occurrences"]
            .as_array()
            .map(Vec::len),
        Some(1),
        "{remaining_evidence:#}"
    );
    assert_eq!(
        remaining_evidence["result"]["edges"][0]["evidenceTruncated"],
        true
    );
    let final_continuation =
        remaining_evidence["result"]["edges"][0]["evidenceContinuation"].clone();
    assert!(final_continuation.is_string());

    let (_, final_evidence) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("final-evidence", final_continuation, 1),
    );
    assert_eq!(
        final_evidence["result"]["edges"][0]["occurrences"]
            .as_array()
            .map(Vec::len),
        Some(1),
        "{final_evidence:#}"
    );
    assert_eq!(
        final_evidence["result"]["edges"][0]["evidenceTruncated"],
        false
    );
    assert!(final_evidence["result"]["continuation"].is_null());

    for (label, pointer, value) in [
        (
            "question",
            "/params/question",
            serde_json::json!(
                "Trace CALLS from semanticGraphOperation to SemanticGraphSha256.parse, please."
            ),
        ),
        (
            "intent",
            "/params/intent",
            serde_json::json!("incoming_impact"),
        ),
        ("module", "/params/scope/module", serde_json::json!("app")),
        (
            "source-set",
            "/params/scope/sourceSet",
            serde_json::json!("main"),
        ),
        (
            "direction",
            "/params/scope/direction",
            serde_json::json!("INCOMING"),
        ),
        (
            "relations",
            "/params/scope/relations",
            serde_json::json!(["REFERENCES"]),
        ),
        (
            "scope-depth",
            "/params/scope/maxDepth",
            serde_json::json!(5),
        ),
        ("depth", "/params/limits/depth", serde_json::json!(5)),
        ("results", "/params/limits/results", serde_json::json!(9)),
    ] {
        let mut request = repository_path_page_request(label, continuation.clone(), 1);
        *request.pointer_mut(pointer).expect("mismatch field") = value;
        if label == "intent" {
            request["params"]["scope"]["direction"] = serde_json::Value::Null;
        }
        let (status, response) = rpc(&home, &config_home, &workspace, request);
        assert!(!status.success(), "{label}: {response:#}");
        assert_eq!(
            response["code"], "INVALID_REPOSITORY_CONTINUATION",
            "{label}: {response:#}"
        );
    }

    let mut forged = continuation
        .as_str()
        .expect("opaque repository continuation")
        .as_bytes()
        .to_vec();
    let final_byte = forged.last_mut().expect("continuation signature");
    *final_byte = if *final_byte == b'0' { b'1' } else { b'0' };
    let forged = String::from_utf8(forged).expect("ASCII continuation");
    let (forged_status, forged_response) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("forged", serde_json::json!(forged), 1),
    );
    assert!(!forged_status.success(), "{forged_response:#}");
    assert_eq!(
        forged_response["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{forged_response:#}"
    );

    let (_, discovery) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "discovery",
            "method": "repository/query",
            "params": {
                "question": "Find the function that builds a semantic graph snapshot.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(discovery["result"]["status"], "ANSWERED", "{discovery:#}");
    assert_eq!(discovery["result"]["queryPlan"]["discovery"], "LEXICAL");
    assert_eq!(
        discovery["result"]["candidates"][0]["name"],
        "buildSemanticGraphSnapshot"
    );
    assert!(
        discovery["result"]["candidates"][0]["matchReasons"]
            .as_array()
            .is_some_and(|reasons| !reasons.is_empty())
    );

    let canonical_key = discovery["result"]["candidates"][0]["canonicalKey"]
        .as_str()
        .expect("discovery candidate has canonical identity");
    let (_, exact_key) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "exact-key",
            "method": "repository/query",
            "params": {
                "question": "This prose must not affect exact-key lookup.",
                "intent": "resolve",
                "canonicalKey": canonical_key,
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(exact_key["result"]["status"], "ANSWERED", "{exact_key:#}");
    assert_eq!(exact_key["result"]["queryPlan"]["discovery"], "EXACT_KEY");
    assert_eq!(
        exact_key["result"]["selectedIdentity"],
        serde_json::Value::String(canonical_key.to_string())
    );
    assert_eq!(
        exact_key["result"]["candidates"].as_array().map(Vec::len),
        Some(1)
    );

    let (_, ambiguous) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ambiguous",
            "method": "repository/query",
            "params": {
                "question": "Resolve parse.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(ambiguous["result"]["status"], "AMBIGUOUS", "{ambiguous:#}");
    assert!(ambiguous["result"]["selectedIdentity"].is_null());
    assert!(
        ambiguous["result"]["candidates"]
            .as_array()
            .is_some_and(|candidates| candidates.len() == 2)
    );

    let (_, architecture) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "architecture",
            "method": "repository/query",
            "params": {
                "question": "Which internal declarations are incoming runtime call hubs?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "RUNTIME_CALLS",
                    "direction": "INCOMING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        architecture["result"]["status"], "ANSWERED",
        "{architecture:#}"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["type"],
        "HIGH_CENTRALITY_INTERNAL_IMPLEMENTATION"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["projection"],
        "RUNTIME_CALLS"
    );
    assert!(
        architecture["result"]["findings"][0]["supportingSubgraph"]["edges"]
            .as_array()
            .is_some_and(|edges| !edges.is_empty())
    );

    std::fs::create_dir_all(workspace.join("docs/explanation")).expect("context fixture directory");
    std::fs::write(
        workspace.join("docs/explanation/compiler-evidence.md"),
        "# Compiler evidence\n\nSemanticGraphSha256 has an exact compiler identity.\n",
    )
    .expect("context fixture document");
    let context_request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "context",
        "method": "repository/query",
        "params": {
            "question": "Which document explains SemanticGraphSha256?",
            "intent": "context_relationship",
            "scope": {"language": "kotlin", "sources": ["markdown"]},
            "limits": {"depth": 6, "results": 10, "evidence": 5}
        }
    });
    let (_, context) = rpc(&home, &config_home, &workspace, context_request.clone());
    assert_eq!(context["result"]["status"], "ANSWERED", "{context:#}");
    assert_eq!(
        context["result"]["canonicalResultModel"], true,
        "{context:#}"
    );
    assert_eq!(
        context["result"]["contextRelations"][0]["kind"],
        "DOCUMENTS"
    );
    assert_eq!(
        context["result"]["contextRelations"][0]["targetName"],
        "SemanticGraphSha256"
    );
    assert_eq!(
        context["result"]["contextRelations"][0]["evidenceClass"],
        "extracted"
    );
    let toon = rpc_output(&home, &config_home, &workspace, "toon", &context_request);
    assert!(toon.status.success());
    let toon_response: serde_json::Value =
        toon_format::decode_default(String::from_utf8_lossy(&toon.stdout).trim())
            .expect("TOON repository response");
    for pointer in [
        "/result/canonicalResultModel",
        "/result/status",
        "/result/question",
        "/result/intent",
        "/result/contextRelations",
        "/result/nodes",
        "/result/evidenceClasses",
    ] {
        assert_eq!(
            toon_response.pointer(pointer),
            context.pointer(pointer),
            "{pointer}"
        );
    }
    let markdown = rpc_output(&home, &config_home, &workspace, "human", &context_request);
    assert!(markdown.status.success());
    let markdown = String::from_utf8_lossy(&markdown.stdout);
    assert!(
        markdown.contains("Kast repository intelligence"),
        "{markdown}"
    );
    assert!(
        markdown.contains("docs/explanation/compiler-evidence.md"),
        "{markdown}"
    );
    assert!(
        markdown.contains("Reproducible query descriptor"),
        "{markdown}"
    );

    std::fs::write(
        workspace.join("docs/explanation/compiler-evidence.md"),
        "# Compiler evidence\n\nThe compiler model lives under `src/main/kotlin/sample/`.\n",
    )
    .expect("path-only context document");
    let (_, inferred_target) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "path-only-context",
            "method": "repository/query",
            "params": {
                "question": "Which exact Kotlin model carries semantic graph hashing evidence?",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["markdown"]},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        inferred_target["result"]["status"], "ANSWERED",
        "{inferred_target:#}"
    );
    assert!(
        inferred_target["result"]["contextRelations"]
            .as_array()
            .is_some_and(|relations| relations.iter().any(|relation| {
                relation["sourcePath"] == "docs/explanation/compiler-evidence.md"
                    && relation["targetName"] == "SemanticGraphSha256"
                    && relation["kind"] == "DOCUMENTS"
                    && relation["evidenceClass"] == "extracted"
            })),
        "{inferred_target:#}"
    );

    let (_, wrong_direction) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "wrong-direction",
            "method": "repository/query",
            "params": {
                "question": "List outgoing CALLS made by SemanticGraphSha256.parse.",
                "intent": "outgoing_impact",
                "scope": {"language": "kotlin", "relations": ["CALLS"], "maxDepth": 1},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        wrong_direction["result"]["status"], "EMPTY",
        "{wrong_direction:#}"
    );

    fixture
        .connection()
        .execute("DROP TABLE semantic_symbols", [])
        .expect("remove graph traversal authority");
    let (pre_traversal_status, pre_traversal) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("pre-traversal-rejection", continuation.clone(), 10),
    );
    assert!(!pre_traversal_status.success(), "{pre_traversal:#}");
    assert_eq!(
        pre_traversal["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{pre_traversal:#}"
    );

    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 42", [])
        .expect("advance graph generation");
    let (stale_status, stale) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_path_page_request("stale", continuation, 1),
    );
    assert!(!stale_status.success(), "{stale:#}");
    assert_eq!(stale["code"], "STALE_REPOSITORY_CONTINUATION", "{stale:#}");
}
