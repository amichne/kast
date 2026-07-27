#[test]
fn repository_paths_carry_exact_identity_occurrences_and_derivations() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let continuation = assert_exact_path_evidence(&home, &config_home, &workspace);
    assert_resolution_and_architecture_views(&home, &config_home, &workspace);
    assert_context_output_views(&home, &config_home, &workspace);
    assert_path_invalidation(&home, &config_home, &workspace, &fixture, continuation);
}
