#[test]
fn repository_discovery_paraphrases_preserve_evidence_derived_outcomes() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_discovery_name_collision(&fixture);
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
              start_offset, end_offset, line)
             VALUES
             (31, 'callable:missingSymbol', 1, NULL, 'FUNCTION', 'missingSymbol',
              'sample.missingSymbol', 'sample.missingSymbol|-|||0', 501, 520, 50)",
            [],
        )
        .expect("lexical missing-symbol distractor");

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

    let explicit_nonselection = resolve(
        "explicit-nonselection",
        "Resolve semanticGraphOperation without choosing between the two declarations.",
    );
    assert_eq!(
        explicit_nonselection["result"]["status"], "AMBIGUOUS",
        "{explicit_nonselection:#}"
    );

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

    let natural_missing = resolve(
        "natural-missing",
        "Does a declaration named DefinitelyMissingRepositoryIntelligenceSymbol exist?",
    );
    assert_eq!(
        natural_missing["result"]["status"], "EMPTY",
        "{natural_missing:#}"
    );
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
