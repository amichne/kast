#[test]
fn private_directory_creation_publishes_each_new_entry_before_success() {
    let fixture = tempfile::tempdir().expect("fixture");
    let state = fixture.path().join("state");
    std::fs::create_dir(&state).expect("existing state directory");
    let plans = state.join("nested/agent-plans");
    let published = std::cell::RefCell::new(Vec::new());

    ensure_private_directory_with(&plans, &|parent| {
        published.borrow_mut().push(parent.to_path_buf());
        Ok(())
    })
    .expect("durably published private directory");

    assert_eq!(published.into_inner(), vec![state.clone(), state.join("nested")]);
    assert!(plans.is_dir());

    let rejected = ensure_private_directory_with(&state.join("rejected"), &|_| {
        Err(CliError::new(
            "KAST_PLAN_STORE_UNAVAILABLE",
            "injected parent-directory barrier failure",
        ))
    });
    assert_eq!(
        rejected.expect_err("barrier failure must reject publication").code,
        "KAST_PLAN_STORE_UNAVAILABLE",
    );
}
