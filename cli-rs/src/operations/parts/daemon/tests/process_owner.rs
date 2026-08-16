#[cfg(unix)]
#[test]
fn exact_legacy_indexer_process_is_detected_without_a_descriptor_or_socket() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    std::fs::create_dir(&workspace).unwrap();
    let root_arg = format!("--workspace-root={}", workspace.display());
    let mut orphan = std::process::Command::new("sh")
        .args([
            "-c",
            "while :; do sleep 1; done",
            INDEXER_STARTER_COMMAND,
            &root_arg,
        ])
        .spawn()
        .unwrap();

    let collision = require_no_legacy_indexer(&workspace);

    let _ = orphan.kill();
    let _ = orphan.wait();
    let collision = collision.expect_err("legacy exact-root indexer must block replacement launch");
    assert_eq!(collision.code, "INDEXER_STORAGE_IN_USE");
}
