use serde_json::Value;

#[test]
fn release_state_and_rust_build_script_agree_on_schema_version_eight() {
    let release_state: Value =
        serde_json::from_str(include_str!("../../packaging/homebrew/release-state.json"))
            .expect("release state must be valid JSON");
    let release_version = release_state["source_index_schema_version"]
        .as_i64()
        .expect("source_index_schema_version must be an integer");
    let generated_version = env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse::<i64>()
        .expect("Rust build script schema version must be numeric");

    assert_eq!(8, release_version);
    assert_eq!(release_version, generated_version);
}
