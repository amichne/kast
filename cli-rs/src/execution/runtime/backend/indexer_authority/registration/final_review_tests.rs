use super::*;

#[test]
fn fresh_cache_log_parent_is_prepared_before_service_registration_final_registration_review_regression()
 {
    let temp = tempfile::tempdir().expect("cache root");
    let log_file = temp
        .path()
        .join("cache/workspaces/workspace/logs/indexer.log");
    let log_parent = log_file.parent().expect("log parent");
    assert!(!log_parent.exists());

    prepare_service_log_parent(&log_file).expect("prepared log parent");

    assert!(log_parent.is_dir());
    assert!(!log_file.exists());
}
