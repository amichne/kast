use super::*;

#[test]
fn agent_add_file_preview_rejects_partial_malformed_or_unbound_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let proposed = "class Added\n";
    let content_file = temp.path().join("Added.kt");
    std::fs::write(&content_file, proposed).expect("proposed file");
    let valid = exact_add_file_preview(&workspace, &target, proposed);
    let mut cases = Vec::new();

    let mut unknown = valid.clone();
    unknown["proof"]["owner"]["untrusted"] = json!(true);
    cases.push(("unknown", unknown));

    let mut incomplete = valid.clone();
    incomplete["proof"]["collisionEvidence"]["dimensions"] = json!([
        "EXACT_DECLARATION_IDENTITIES",
        "COMPLETE_OWNING_SOURCE_SCOPE",
        "COMPLETE_DEPENDENT_SCOPE"
    ]);
    cases.push(("incomplete", incomplete));

    let mut image_mismatch = valid.clone();
    let other = b"class Other\n";
    image_mismatch["postimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(other),
        "sha256": replacement_sha256(other),
    });
    image_mismatch["proof"]["postimageSha256"] = json!(replacement_sha256(other));
    cases.push(("image-mismatch", image_mismatch));

    let mut path_mismatch = valid.clone();
    path_mismatch["proof"]["targetPath"] =
        json!(workspace.join("src/main/kotlin/Other.kt"));
    cases.push(("path-mismatch", path_mismatch));

    let mut cardinality = valid.clone();
    cardinality["proof"]["outboundEvidence"]["cardinality"] = json!(1);
    cases.push(("cardinality", cardinality));

    let mut schema = valid;
    schema["schemaVersion"] = json!(4);
    cases.push(("schema", schema));

    for (name, result) in cases {
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &temp.path().join(format!("add-file-{name}.sock")),
            vec![("raw/plan-add-file", result)],
        );
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "add-file",
                "--file-path",
                target.to_str().expect("target"),
                "--content-file",
                content_file.to_str().expect("content"),
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .unwrap_or_else(|error| panic!("{name}: {error}"));
        assert!(!output.status.success(), "{name}: {output:?}");
        let output: Value = serde_json::from_slice(&output.stdout).expect("error JSON");
        assert_eq!(output["error"]["code"], "INVALID_ADDITION_PREVIEW", "{name}");
        backend.join().unwrap_or_else(|_| panic!("{name} backend"));
    }
}

#[test]
fn agent_add_declaration_preview_rejects_partial_image_and_policy_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    let preimage = b"class Existing\n";
    std::fs::write(&target, preimage).expect("existing file");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical target");
    let declaration = "class Added";
    let content_file = temp.path().join("declaration.kt");
    std::fs::write(&content_file, declaration).expect("proposed declaration");
    let valid = exact_add_declaration_preview(&workspace, &target, preimage, declaration);
    let mut cases = Vec::new();

    let mut unknown = valid.clone();
    unknown["proof"]["insertion"]["untrusted"] = json!(true);
    cases.push(("unknown", unknown));

    let mut incomplete = valid.clone();
    incomplete["proof"]["rebindingBaseline"]["dimensions"] =
        json!(["EXACT_OCCURRENCE_CARDINALITY"]);
    cases.push(("incomplete", incomplete));

    let mut preimage_mismatch = valid.clone();
    preimage_mismatch["image"]["preimage"] = json!({
        "contentBase64": STANDARD_BASE64.encode(b"class Changed\n"),
        "sha256": replacement_sha256(b"class Changed\n"),
    });
    cases.push(("image-mismatch", preimage_mismatch));

    let mut path_mismatch = valid.clone();
    path_mismatch["proof"]["targetPath"] =
        json!(workspace.join("src/main/kotlin/Other.kt"));
    cases.push(("path-mismatch", path_mismatch));

    let mut insertion = valid.clone();
    insertion["proof"]["insertion"]["offset"] = json!(0);
    cases.push(("insertion", insertion));

    let mut policy = valid.clone();
    policy["proof"]["newlinePolicy"] = json!("REWRITE_ALL_NEWLINES");
    cases.push(("policy", policy));

    let mut schema = valid;
    schema["schemaVersion"] = json!(4);
    cases.push(("schema", schema));

    for (name, result) in cases {
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &temp.path().join(format!("add-declaration-{name}.sock")),
            vec![("raw/plan-add-declaration", result)],
        );
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "add-declaration",
                "--inside-file",
                target.to_str().expect("target"),
                "--at",
                "file-bottom",
                "--content-file",
                content_file.to_str().expect("content"),
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .unwrap_or_else(|error| panic!("{name}: {error}"));
        assert!(!output.status.success(), "{name}: {output:?}");
        let output: Value = serde_json::from_slice(&output.stdout).expect("error JSON");
        assert_eq!(output["error"]["code"], "INVALID_ADDITION_PREVIEW", "{name}");
        backend.join().unwrap_or_else(|_| panic!("{name} backend"));
    }
}
