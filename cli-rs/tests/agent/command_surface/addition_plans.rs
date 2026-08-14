fn addition_collision_dimensions() -> Value {
    json!([
        "EXACT_DECLARATION_IDENTITIES",
        "COMPLETE_OWNING_SOURCE_SCOPE",
        "COMPLETE_DEPENDENT_SCOPE",
        "NO_COMPILER_COLLISION",
    ])
}

fn addition_rebinding_dimensions() -> Value {
    json!([
        "EXACT_OCCURRENCE_CARDINALITY",
        "COMPLETE_DEPENDENT_SCOPE",
        "COMPLETE_IMPLICIT_LOOKUP_SCOPE",
        "COMPLETE_JAVA_LOOKUP_SCOPE",
        "EVERY_CURRENT_BINDING_CAPTURED",
        "VIRTUAL_PROPOSED_BINDINGS_EQUAL_BASELINE",
    ])
}

fn addition_owner(workspace: &Path) -> Value {
    json!({
        "sourceRoot": workspace.join("src/main/kotlin"),
        "ideaModuleName": "root.main",
        "gradleBuildRoot": workspace,
        "gradleProjectPath": ":",
        "sourceSetName": "main",
    })
}

fn addition_declaration(name: &str, end_offset: usize) -> Value {
    json!({
        "packageIdentity": {"type": "ROOT"},
        "name": name,
        "kind": "CLASS",
        "relativeRange": {"startOffset": 0, "endOffset": end_offset},
        "collisionSignature": "1".repeat(64),
    })
}

fn addition_context(context_file_hashes: Vec<Value>) -> Value {
    json!({
        "requiredGeneration": 7,
        "projectModelFingerprint": "2".repeat(64),
        "classpathFingerprint": "3".repeat(64),
        "contextFileHashes": context_file_hashes,
    })
}

fn exact_add_file_preview(workspace: &Path, target: &Path, proposed: &str) -> Value {
    let postimage_sha256 = replacement_sha256(proposed.as_bytes());
    json!({
        "proposedContent": proposed,
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(proposed.as_bytes()),
            "sha256": postimage_sha256,
        },
        "proof": {
            "targetPath": target,
            "targetState": "ABSENT",
            "owner": addition_owner(workspace),
            "packageIdentity": {"type": "ROOT"},
            "declarations": [addition_declaration("Added", "class Added".encode_utf16().count())],
            "context": addition_context(Vec::new()),
            "collisionEvidence": {
                "declarationCardinality": 1,
                "dimensions": addition_collision_dimensions(),
            },
            "outboundEvidence": {"cardinality": 0, "occurrences": []},
            "rebindingBaseline": {
                "cardinality": 0,
                "dimensions": addition_rebinding_dimensions(),
                "occurrences": [],
            },
            "postimageSha256": postimage_sha256,
        },
        "schemaVersion": api_schema_version(),
    })
}

#[test]
fn agent_add_file_preview_requires_closed_compiler_authority() {
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
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("add-file.sock"),
        vec![(
            "raw/plan-add-file",
            exact_add_file_preview(&workspace, &target, proposed),
        )],
    );

    let plan = kast(&home, &config_home)
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
        .expect("add-file preview");

    assert!(
        plan.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr),
    );
    let plan: Value = serde_json::from_slice(&plan.stdout).expect("plan JSON");
    assert_eq!(plan["result"]["type"], "KAST_AGENT_MUTATION_RESULT");
    assert_eq!(plan["result"]["plan"]["method"], "symbol/add-file");
    assert_eq!(
        plan["result"]["plan"]["preview"]["proof"]["targetState"],
        "ABSENT"
    );
    let requests = backend.join().expect("add-file backend");
    assert_eq!(requests[2]["method"], "raw/plan-add-file");
    assert_eq!(requests[2]["params"]["targetPath"], json!(target));
    assert_eq!(requests[2]["params"]["proposedContent"], proposed);
}

#[test]
fn agent_add_declaration_requires_verified_change_workflow() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    std::fs::write(&target, b"class Existing\n").expect("existing file");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical target");
    let content_file = temp.path().join("declaration.kt");
    std::fs::write(&content_file, "class Added").expect("proposed declaration");

    let result = kast(&home, &config_home)
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
        .expect("retired add-declaration command");

    assert!(!result.status.success(), "{result:?}");
    let result: Value = serde_json::from_slice(&result.stdout).expect("retirement JSON");
    assert_eq!(
        result["error"]["code"],
        "KAST_VERIFIED_ADD_DECLARATION_WORKFLOW_REQUIRED",
    );
}

#[path = "cases/addition_negative.rs"]
mod addition_negative;
