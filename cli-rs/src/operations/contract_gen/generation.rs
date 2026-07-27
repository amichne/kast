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
        let references = manifest_dir.join("protocol/source");
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
    files.insert(
        yaml_path.to_path_buf(),
        serde_yaml::to_string(&canonical_json_value(catalog))?,
    );
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
                let discriminator = catalog_schema::variant_discriminator(command, method)?;
                for (variant_name, variant_request) in variants {
                    for kind in SampleKind::ALL {
                        let mut params = Map::new();
                        params.insert(discriminator.clone(), Value::String(variant_name.clone()));
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
    let mut content = serde_json::to_string_pretty(&canonical_json_value(value))?;
    content.push('\n');
    Ok(content)
}

fn canonical_json_value(value: &Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(items.iter().map(canonical_json_value).collect()),
        Value::Object(fields) => {
            let mut sorted = Map::new();
            let mut entries = BTreeMap::new();
            for (key, value) in fields {
                entries.insert(key, value);
            }
            for (key, value) in entries {
                sorted.insert(key.clone(), canonical_json_value(value));
            }
            Value::Object(sorted)
        }
        _ => value.clone(),
    }
}

fn request_payload(method: &str, params: Value) -> Result<Value> {
    Ok(serde_json::to_value(JsonRpcRequest {
        jsonrpc: "2.0",
        method: method.to_string(),
        params,
        id: 1,
    })?)
}
