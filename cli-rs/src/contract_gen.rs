use crate::catalog_schema;
use crate::error::{CliError, Result};
use serde::Serialize;
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

const PATH_SAMPLE: &str = "/absolute/path/to/workspace/src/main/kotlin/example/Widget.kt";
const WORKSPACE_SAMPLE: &str = "/absolute/path/to/workspace";

#[derive(Debug, Clone)]
pub struct ContractPaths {
    pub catalog: PathBuf,
    pub yaml: PathBuf,
    pub samples_root: PathBuf,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContractGenerationReport {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checked: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub written: Option<usize>,
}

#[derive(Debug, Serialize)]
struct JsonRpcRequest {
    jsonrpc: &'static str,
    method: String,
    params: Value,
    id: i64,
}

impl ContractPaths {
    pub fn defaults(manifest_dir: &Path) -> Self {
        let references = manifest_dir.join("resources/kast-skill/references");
        Self {
            catalog: references.join("commands.json"),
            yaml: references.join("commands.yaml"),
            samples_root: references.join("requests"),
        }
    }
}

pub fn check(paths: &ContractPaths) -> Result<ContractGenerationReport> {
    let files = generated_files(paths)?;
    let errors = check_files(&files, &paths.samples_root)?;
    if !errors.is_empty() {
        return Err(CliError::new(
            "RPC_CONTRACT_STALE",
            format!("RPC contract artifacts are stale:\n{}", errors.join("\n")),
        ));
    }
    Ok(ContractGenerationReport {
        ok: true,
        checked: Some(files.len()),
        written: None,
    })
}

pub fn write(paths: &ContractPaths) -> Result<ContractGenerationReport> {
    let files = generated_files(paths)?;
    write_files(&files, &paths.samples_root)?;
    Ok(ContractGenerationReport {
        ok: true,
        checked: None,
        written: Some(files.len()),
    })
}

pub fn generated_files(paths: &ContractPaths) -> Result<BTreeMap<PathBuf, String>> {
    let catalog = load_catalog(&paths.catalog)?;
    generated_files_from_catalog(&catalog, &paths.yaml, &paths.samples_root)
}

pub fn generated_files_from_catalog(
    catalog: &Value,
    yaml_path: &Path,
    samples_root: &Path,
) -> Result<BTreeMap<PathBuf, String>> {
    let mut files = BTreeMap::new();
    files.insert(yaml_path.to_path_buf(), serde_yaml::to_string(catalog)?);
    let schemas = catalog_schema::request_schemas(catalog)?;
    for (method, command) in commands(catalog)? {
        let category = command
            .get("category")
            .and_then(Value::as_str)
            .ok_or_else(|| {
                CliError::new(
                    "RPC_CATALOG_INVALID",
                    format!("Command `{method}` must include a category."),
                )
            })?;
        let base = request_path(samples_root, category, method);
        let schema = schemas.get(method).ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("No generated request schema was available for `{method}`."),
            )
        })?;
        files.insert(base.join("request.schema.json"), json_file_content(schema)?);
        let variants = command.get("variants").and_then(Value::as_object);
        match variants {
            Some(variants) if !variants.is_empty() => {
                let type_enum = command["request"]["fields"]["type"]
                    .get("enum")
                    .and_then(Value::as_array)
                    .ok_or_else(|| {
                        CliError::new(
                            "RPC_CATALOG_INVALID",
                            format!("Variant command `{method}` must define a type enum."),
                        )
                    })?;
                for (variant_name, variant_request) in variants {
                    if !type_enum.iter().any(|value| value == variant_name) {
                        return Err(CliError::new(
                            "RPC_CATALOG_INVALID",
                            format!(
                                "{method} variant {variant_name} is missing from the type enum."
                            ),
                        ));
                    }
                    for kind in SampleKind::ALL {
                        let mut params = Map::new();
                        params.insert("type".to_string(), Value::String(variant_name.clone()));
                        let sample = sample_request(variant_request, kind.maximal())?;
                        for (name, value) in sample {
                            params.insert(name, value);
                        }
                        let request = request_payload(method, Value::Object(params))?;
                        files.insert(
                            base.join(variant_name).join(kind.file_name()),
                            json_file_content(&request)?,
                        );
                    }
                }
            }
            _ => {
                for kind in SampleKind::ALL {
                    let params = Value::Object(sample_request(
                        command_request(command, method)?,
                        kind.maximal(),
                    )?);
                    let request = request_payload(method, params)?;
                    files.insert(base.join(kind.file_name()), json_file_content(&request)?);
                }
            }
        }
    }
    Ok(files)
}

pub fn sample_json_paths(samples_root: &Path) -> Result<Vec<PathBuf>> {
    if !samples_root.exists() {
        return Ok(Vec::new());
    }
    let mut paths = Vec::new();
    collect_sample_json_paths(samples_root, &mut paths)?;
    paths.sort();
    Ok(paths)
}

pub fn is_sample_json_path(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| matches!(name, "minimal.json" | "maximal.json"))
}

fn collect_sample_json_paths(path: &Path, paths: &mut Vec<PathBuf>) -> Result<()> {
    for entry in fs::read_dir(path)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            collect_sample_json_paths(&path, paths)?;
        } else if is_sample_json_path(&path) {
            paths.push(path);
        }
    }
    Ok(())
}

fn check_files(files: &BTreeMap<PathBuf, String>, samples_root: &Path) -> Result<Vec<String>> {
    let mut errors = Vec::new();
    for (path, expected) in files {
        if !path.exists() {
            errors.push(format!("missing generated file: {}", path.display()));
            continue;
        }
        let actual = fs::read_to_string(path)?;
        if actual != *expected {
            errors.push(format!("outdated generated file: {}", path.display()));
        }
    }
    if samples_root.exists() {
        let mut stack = vec![samples_root.to_path_buf()];
        while let Some(path) = stack.pop() {
            for entry in fs::read_dir(&path)? {
                let entry = entry?;
                let path = entry.path();
                if path.is_dir() {
                    stack.push(path);
                } else if path.extension().and_then(|ext| ext.to_str()) == Some("json")
                    && !files.contains_key(&path)
                {
                    errors.push(format!("unexpected generated file: {}", path.display()));
                }
            }
        }
    }
    Ok(errors)
}

fn write_files(files: &BTreeMap<PathBuf, String>, samples_root: &Path) -> Result<()> {
    if samples_root.exists() {
        fs::remove_dir_all(samples_root)?;
    }
    for (path, content) in files {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(path, content)?;
    }
    Ok(())
}

fn load_catalog(path: &Path) -> Result<Value> {
    let content = fs::read_to_string(path)?;
    Ok(serde_json::from_str(&content)?)
}

fn commands(catalog: &Value) -> Result<&Map<String, Value>> {
    catalog
        .get("commands")
        .and_then(Value::as_object)
        .ok_or_else(|| CliError::new("RPC_CATALOG_INVALID", "Catalog commands must be an object."))
}

fn command_request<'a>(command: &'a Value, method: &str) -> Result<&'a Value> {
    command.get("request").ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Command `{method}` must include a request object."),
        )
    })
}

fn request_path(samples_root: &Path, category: &str, method: &str) -> PathBuf {
    let mut parts = method.split('/');
    match parts.next() {
        Some(first) if first == category => {
            parts.fold(samples_root.join(category), |base, part| base.join(part))
        }
        _ => method
            .split('/')
            .fold(samples_root.join(category), |base, part| base.join(part)),
    }
}

fn json_file_content(value: &Value) -> Result<String> {
    let mut content = serde_json::to_string_pretty(value)?;
    content.push('\n');
    Ok(content)
}

fn request_payload(method: &str, params: Value) -> Result<Value> {
    Ok(serde_json::to_value(JsonRpcRequest {
        jsonrpc: "2.0",
        method: method.to_string(),
        params,
        id: 1,
    })?)
}

fn sample_request(request: &Value, maximal: bool) -> Result<Map<String, Value>> {
    let fields = request
        .get("fields")
        .and_then(Value::as_object)
        .ok_or_else(|| CliError::new("RPC_CATALOG_INVALID", "Request fields must be an object."))?;
    let required = request_required(request)?;
    let mut payload = Map::new();
    for (name, field) in fields {
        if maximal || required.iter().any(|required_name| required_name == name) {
            payload.insert(name.clone(), sample_field(name, field, maximal)?);
        }
    }
    Ok(payload)
}

fn request_required(request: &Value) -> Result<Vec<String>> {
    if let Some(required) = request.get("required") {
        return required
            .as_array()
            .ok_or_else(|| {
                CliError::new(
                    "RPC_CATALOG_INVALID",
                    "Catalog required field must be an array.",
                )
            })?
            .iter()
            .map(|value| {
                value.as_str().map(str::to_string).ok_or_else(|| {
                    CliError::new(
                        "RPC_CATALOG_INVALID",
                        "Catalog required entries must be strings.",
                    )
                })
            })
            .collect();
    }
    Ok(request
        .get("fields")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .filter(|(_, field)| field.get("optional").and_then(Value::as_bool) != Some(true))
        .map(|(name, _)| name.clone())
        .collect())
}

fn sample_field(name: &str, field: &Value, maximal: bool) -> Result<Value> {
    if let Some(enum_values) = field.get("enum").and_then(Value::as_array)
        && let Some(value) = if maximal {
            enum_values.last()
        } else {
            enum_values.first()
        }
    {
        return Ok(value.clone());
    }

    match field.get("type").and_then(Value::as_str) {
        Some("string") => Ok(Value::String(sample_string(name))),
        Some("integer") => Ok(Value::Number(sample_integer(name).into())),
        Some("boolean") => Ok(Value::Bool(true)),
        Some("array") => sample_array(name, field, maximal),
        Some("object") => {
            if let Some(fields) = field.get("fields") {
                let mut nested = Map::new();
                nested.insert("fields".to_string(), fields.clone());
                if let Some(required) = field.get("required") {
                    nested.insert("required".to_string(), required.clone());
                }
                Ok(Value::Object(sample_request(
                    &Value::Object(nested),
                    maximal,
                )?))
            } else {
                Ok(Value::Object(sample_open_object(name)))
            }
        }
        Some(other) => Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Unsupported sample field type `{other}` for `{name}`."),
        )),
        None => Ok(Value::Object(sample_open_object(name))),
    }
}

fn sample_array(name: &str, field: &Value, maximal: bool) -> Result<Value> {
    match field.get("items") {
        Some(Value::String(items)) if items == "string" => {
            let item = if name == "filePaths" {
                PATH_SAMPLE.to_string()
            } else {
                let singular = name.strip_suffix('s').unwrap_or(name);
                sample_string(if singular.is_empty() {
                    "value"
                } else {
                    singular
                })
            };
            Ok(Value::Array(vec![Value::String(item)]))
        }
        Some(Value::String(items)) if items == "integer" => Ok(Value::Array(vec![1.into()])),
        Some(Value::String(items)) if items == "boolean" => {
            Ok(Value::Array(vec![Value::Bool(true)]))
        }
        Some(Value::String(_)) | None => {
            Ok(Value::Array(vec![Value::Object(sample_open_object(name))]))
        }
        Some(items) if items.is_object() => {
            Ok(Value::Array(vec![sample_field("item", items, maximal)?]))
        }
        Some(_) => Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Array field `{name}` has invalid item schema."),
        )),
    }
}

fn sample_integer(name: &str) -> i64 {
    let lower = name.to_ascii_lowercase();
    if lower.contains("offset") {
        return 128;
    }
    if lower == "endoffset" {
        return 180;
    }
    if lower.contains("line") {
        return 42;
    }
    if lower.contains("depth") {
        return 2;
    }
    if lower.contains("timeout") {
        return 5000;
    }
    if lower.contains("maxchildren") {
        return 10;
    }
    if lower.contains("maxtotal") {
        return 50;
    }
    if lower.contains("limit") || lower.contains("max") {
        return 25;
    }
    1
}

fn sample_string(name: &str) -> String {
    let lower = name.to_ascii_lowercase();
    match lower.as_str() {
        "workspaceroot" => WORKSPACE_SAMPLE.to_string(),
        "filepath" | "targetfile" | "contentfile" | "filehint" => PATH_SAMPLE.to_string(),
        "fileglob" => "**/*.kt".to_string(),
        "folderfilter" => "src/main/kotlin".to_string(),
        "modulename" => ":analysis-api".to_string(),
        "modulepath" => ":app".to_string(),
        "sourcesset" | "sourceset" => "main".to_string(),
        "packageprefix" => "com.example".to_string(),
        "fqname" | "fqnameprefix" | "containingtype" => "com.example.Widget".to_string(),
        "newname" => "RenamedWidget".to_string(),
        "symbol" | "targetsymbol" | "query" | "pattern" => "Widget".to_string(),
        "codesnippet" => "val widget = Widget()".to_string(),
        "diagnosticcode" => "UNUSED_IMPORT".to_string(),
        "content" => "fun added() = Unit\n".to_string(),
        _ => format!("example-{name}"),
    }
}

fn sample_open_object(name: &str) -> Map<String, Value> {
    let lower = name.to_ascii_lowercase();
    let mut object = Map::new();
    match lower.as_str() {
        "position" => {
            object.insert(
                "filePath".to_string(),
                Value::String(PATH_SAMPLE.to_string()),
            );
            object.insert("offset".to_string(), Value::Number(128.into()));
        }
        "edits" | "item" => {
            object.insert(
                "filePath".to_string(),
                Value::String(PATH_SAMPLE.to_string()),
            );
            object.insert("startOffset".to_string(), Value::Number(120.into()));
            object.insert("endOffset".to_string(), Value::Number(180.into()));
            object.insert(
                "content".to_string(),
                Value::String("val renamed = Widget()\n".to_string()),
            );
        }
        "filehashes" => {
            object.insert(
                "filePath".to_string(),
                Value::String(PATH_SAMPLE.to_string()),
            );
            object.insert("sha256".to_string(), Value::String("abc123".to_string()));
        }
        "fileoperations" => {
            object.insert("type".to_string(), Value::String("CREATE_FILE".to_string()));
            object.insert(
                "filePath".to_string(),
                Value::String(PATH_SAMPLE.to_string()),
            );
        }
        _ => {
            object.insert("example".to_string(), Value::Bool(true));
        }
    }
    object
}

#[derive(Debug, Clone, Copy)]
enum SampleKind {
    Minimal,
    Maximal,
}

impl SampleKind {
    const ALL: [Self; 2] = [Self::Minimal, Self::Maximal];

    fn file_name(self) -> &'static str {
        match self {
            Self::Minimal => "minimal.json",
            Self::Maximal => "maximal.json",
        }
    }

    fn maximal(self) -> bool {
        matches!(self, Self::Maximal)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn generated_files_include_yaml_samples_and_schema() {
        let catalog = json!({
            "commands": {
                "symbol/example": {
                    "method": "symbol/example",
                    "category": "symbol",
                    "request": {
                        "fields": {
                            "query": { "type": "string" }
                        },
                        "required": ["query"]
                    }
                }
            }
        });
        let files = generated_files_from_catalog(
            &catalog,
            Path::new("/tmp/commands.yaml"),
            Path::new("/tmp/requests"),
        )
        .expect("generated files");
        assert!(files.contains_key(Path::new("/tmp/commands.yaml")));
        assert!(files.contains_key(Path::new("/tmp/requests/symbol/example/minimal.json")));
        assert!(files.contains_key(Path::new("/tmp/requests/symbol/example/maximal.json")));
        assert!(files.contains_key(Path::new(
            "/tmp/requests/symbol/example/request.schema.json"
        )));
    }

    #[test]
    fn sample_path_filter_excludes_generated_schemas() {
        assert!(is_sample_json_path(Path::new("minimal.json")));
        assert!(is_sample_json_path(Path::new("maximal.json")));
        assert!(!is_sample_json_path(Path::new("request.schema.json")));
    }
}
