use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let repo_root = manifest_dir.parent().expect("repo root");
    println!("cargo:rerun-if-env-changed=KAST_RELEASE_REVISION");
    let release_revision =
        env::var("KAST_RELEASE_REVISION").unwrap_or_else(|_| git_release_revision(repo_root));
    require_release_revision(&release_revision);
    println!("cargo:rustc-env=KAST_RELEASE_REVISION={release_revision}");

    println!("cargo:rerun-if-env-changed=KAST_LOCAL_SOURCE_SHA256");
    if let Ok(source_sha256) = env::var("KAST_LOCAL_SOURCE_SHA256") {
        if source_sha256.len() != 64 || !source_sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            panic!("KAST_LOCAL_SOURCE_SHA256 must be exactly 64 hexadecimal characters");
        }
        println!("cargo:rustc-env=KAST_LOCAL_SOURCE_SHA256={source_sha256}");
    }

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
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

    let output = out_dir.join("source_index_schema.rs");
    fs::write(
        output,
        format!("pub(crate) const SOURCE_INDEX_SCHEMA_VERSION: i64 = {version};\n"),
    )
    .expect("write generated source-index schema");
    println!("cargo:rustc-env=KAST_SOURCE_INDEX_SCHEMA_VERSION={version}");

    let command_catalog = manifest_dir.join("protocol/source/commands.json");
    println!("cargo:rerun-if-changed={}", command_catalog.display());
    let catalog_content = fs::read_to_string(&command_catalog).unwrap_or_else(|error| {
        panic!("failed to read {}: {error}", command_catalog.display());
    });
    let routes = lsp_custom_routes(&catalog_content).unwrap_or_else(|error| {
        panic!("invalid {}: {error}", command_catalog.display());
    });
    fs::write(out_dir.join("lsp_custom_routes.rs"), routes)
        .expect("write generated LSP custom routes");
}

fn git_release_revision(repo_root: &std::path::Path) -> String {
    let head_path = Command::new("git")
        .args(["rev-parse", "--git-path", "HEAD"])
        .current_dir(repo_root)
        .output()
        .expect("git must be available or KAST_RELEASE_REVISION must be set");
    if !head_path.status.success() {
        panic!("git metadata is unavailable; set KAST_RELEASE_REVISION explicitly");
    }
    let head_path = String::from_utf8(head_path.stdout)
        .expect("git HEAD path must be UTF-8")
        .trim()
        .to_string();
    let head_path = repo_root.join(head_path);
    println!("cargo:rerun-if-changed={}", head_path.display());

    let output = Command::new("git")
        .args(["rev-parse", "HEAD"])
        .current_dir(repo_root)
        .output()
        .expect("git must be available or KAST_RELEASE_REVISION must be set");
    if !output.status.success() {
        panic!("git revision is unavailable; set KAST_RELEASE_REVISION explicitly");
    }
    String::from_utf8(output.stdout)
        .expect("git revision must be UTF-8")
        .trim()
        .to_string()
}

fn require_release_revision(revision: &str) {
    if revision.len() != 40
        || !revision
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        panic!("KAST_RELEASE_REVISION must be exactly 40 lowercase hexadecimal characters");
    }
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

fn lsp_custom_routes(content: &str) -> Result<String, String> {
    let catalog: serde_json::Value =
        serde_json::from_str(content).map_err(|error| error.to_string())?;
    let commands = catalog
        .get("commands")
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| "commands must be an object".to_string())?;
    let categories = catalog
        .get("categories")
        .and_then(serde_json::Value::as_object)
        .ok_or_else(|| "categories must be an object".to_string())?;

    let mut output = String::from("const KAST_CUSTOM_LSP_ROUTES: &[KastCustomLspRoute] = &[\n");
    for category in ["symbol", "database", "system"] {
        let methods = categories
            .get(category)
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| format!("category {category} must be an array"))?;
        for method in methods {
            let method = method
                .as_str()
                .ok_or_else(|| format!("category {category} contains a non-string method"))?;
            let command = commands
                .get(method)
                .ok_or_else(|| format!("category {category} references missing method {method}"))?;
            let declared_category = command
                .get("category")
                .and_then(serde_json::Value::as_str)
                .ok_or_else(|| format!("method {method} is missing category"))?;
            if declared_category != category {
                return Err(format!(
                    "method {method} declares category {declared_category}, expected {category}"
                ));
            }
            output.push_str("    KastCustomLspRoute {\n");
            output.push_str(&format!(
                "        lsp_method: {},\n",
                rust_string_literal(&lsp_method_for_rpc_method(method)?)
            ));
            output.push_str(&format!(
                "        rpc_method: {},\n",
                rust_string_literal(method)
            ));
            output.push_str(&format!(
                "        inject_workspace_root: {},\n",
                category == "symbol"
            ));
            output.push_str("    },\n");
        }
    }
    output.push_str("];\n");
    Ok(output)
}

fn lsp_method_for_rpc_method(method: &str) -> Result<String, String> {
    let mut parts = method.split('/');
    let Some(first) = parts.next() else {
        return Err("method must not be empty".to_string());
    };
    if first.is_empty() {
        return Err(format!("method {method} has an empty first segment"));
    }
    let mut lsp_method = format!("kast/{first}");
    for part in parts {
        if part.is_empty() {
            return Err(format!("method {method} has an empty segment"));
        }
        for word in part.split('-') {
            if word.is_empty() {
                return Err(format!("method {method} has an empty kebab segment"));
            }
            let mut chars = word.chars();
            let first = chars
                .next()
                .expect("word is known non-empty")
                .to_ascii_uppercase();
            lsp_method.push(first);
            lsp_method.extend(chars);
        }
    }
    Ok(lsp_method)
}

fn rust_string_literal(value: &str) -> String {
    format!("{value:?}")
}
