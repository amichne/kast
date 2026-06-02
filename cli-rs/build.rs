use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let release_state = manifest_dir
        .parent()
        .expect("repo root")
        .join("packaging/homebrew/release-state.json");
    println!("cargo:rerun-if-changed={}", release_state.display());

    let content = fs::read_to_string(&release_state).unwrap_or_else(|error| {
        panic!("failed to read {}: {error}", release_state.display());
    });
    let version = source_index_schema_version(&content).unwrap_or_else(|error| {
        panic!("invalid {}: {error}", release_state.display());
    });

    let output =
        PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("source_index_schema.rs");
    fs::write(
        output,
        format!("pub(crate) const SOURCE_INDEX_SCHEMA_VERSION: i64 = {version};\n"),
    )
    .expect("write generated source-index schema");
    println!("cargo:rustc-env=KAST_SOURCE_INDEX_SCHEMA_VERSION={version}");
}

fn source_index_schema_version(content: &str) -> Result<i64, String> {
    let key = "\"source_index_schema_version\"";
    let count = content.matches(key).count();
    if count != 1 {
        return Err(format!("expected exactly one {key} field, found {count}"));
    }
    let (_, after_key) = content
        .split_once(key)
        .ok_or_else(|| format!("missing {key}"))?;
    let (_, after_colon) = after_key
        .split_once(':')
        .ok_or_else(|| format!("{key} must have a value"))?;
    let digits: String = after_colon
        .trim_start()
        .chars()
        .take_while(|ch| ch.is_ascii_digit())
        .collect();
    if digits.is_empty() {
        return Err(format!("{key} must be a positive integer"));
    }
    let version = digits
        .parse::<i64>()
        .map_err(|error| format!("{key} is not a valid integer: {error}"))?;
    if version <= 0 {
        return Err(format!("{key} must be positive"));
    }
    Ok(version)
}
