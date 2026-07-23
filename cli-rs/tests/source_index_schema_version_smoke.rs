#[test]
fn protocol_and_rust_build_script_agree_on_schema_version_eleven() {
    let protocol_version = include_str!("../protocol/source-index-schema-version.txt")
        .trim()
        .parse::<i64>()
        .expect("protocol schema version must be numeric");
    let generated_version = env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse::<i64>()
        .expect("Rust build script schema version must be numeric");

    assert_eq!(11, protocol_version);
    assert_eq!(protocol_version, generated_version);
}
