use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=KAST_LOCAL_SOURCE_SHA256");
    if let Ok(source_sha256) = env::var("KAST_LOCAL_SOURCE_SHA256") {
        if source_sha256.len() != 64 || !source_sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            panic!("KAST_LOCAL_SOURCE_SHA256 must be exactly 64 hexadecimal characters");
        }
        println!("cargo:rustc-env=KAST_LOCAL_SOURCE_SHA256={source_sha256}");
    }

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    let schema_version_file = manifest_dir.join("protocol/source-index-schema-version.txt");
    println!("cargo:rerun-if-changed={}", schema_version_file.display());

    let content = fs::read_to_string(&schema_version_file).unwrap_or_else(|error| {
        panic!("failed to read {}: {error}", schema_version_file.display());
    });
    let version = source_index_schema_version(&content).unwrap_or_else(|error| {
        panic!("invalid {}: {error}", schema_version_file.display());
    });

    let output = out_dir.join("source_index_schema.rs");
    fs::write(
        output,
        format!("pub(crate) const SOURCE_INDEX_SCHEMA_VERSION: i64 = {version};\n"),
    )
    .expect("write generated source-index schema");
    println!("cargo:rustc-env=KAST_SOURCE_INDEX_SCHEMA_VERSION={version}");

    let api_schema_version_file = manifest_dir.join("protocol/api-schema-version.txt");
    let api_schema_version = read_positive_u32_version(&api_schema_version_file);
    let install_receipt_schema_version_file =
        manifest_dir.join("protocol/install-receipt-schema-version.txt");
    let install_receipt_schema_version =
        read_positive_u32_version(&install_receipt_schema_version_file);
    let protocol_schema_versions = format!(
        "pub(crate) const API_SCHEMA_VERSION: u32 = {api_schema_version};\n\
         pub(crate) const INSTALL_RECEIPT_SCHEMA_VERSION: u32 = {install_receipt_schema_version};\n"
    );
    fs::write(
        out_dir.join("protocol_schema_versions.rs"),
        protocol_schema_versions,
    )
    .expect("write generated protocol schema versions");
    println!("cargo:rustc-env=KAST_API_SCHEMA_VERSION={api_schema_version}");
    println!(
        "cargo:rustc-env=KAST_INSTALL_RECEIPT_SCHEMA_VERSION={install_receipt_schema_version}"
    );
}

fn read_positive_u32_version(path: &std::path::Path) -> u32 {
    println!("cargo:rerun-if-changed={}", path.display());
    let content = fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    let version = content
        .trim()
        .parse::<u32>()
        .unwrap_or_else(|error| panic!("invalid {}: {error}", path.display()));
    assert!(version > 0, "{} must be positive", path.display());
    version
}

fn source_index_schema_version(content: &str) -> Result<i64, String> {
    let version = content
        .trim()
        .parse::<i64>()
        .map_err(|error| format!("schema version is not an integer: {error}"))?;
    if version <= 0 {
        return Err("schema version must be positive".to_string());
    }
    Ok(version)
}
