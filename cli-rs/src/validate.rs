use crate::catalog_schema;
use crate::cli::ValidateArgs;
use crate::contract_gen;
use crate::error::{CliError, Result};
use serde::Serialize;
use serde_json::Value;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValidateReport {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub validated: Option<usize>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<ValidationFinding>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValidationFinding {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    pub path: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub schema_path: Option<String>,
}

pub fn run(args: ValidateArgs) -> Result<ValidateReport> {
    let catalog = embedded_catalog()?;
    if args.all_samples {
        let samples_root = args.samples_root.unwrap_or_else(default_samples_root);
        return validate_samples(&samples_root, &catalog);
    }
    let (request, source) = load_request(args.request.as_deref(), args.request_file.as_deref())?;
    validate_request(&request, &catalog, source)
}

pub fn validate_request(
    request: &Value,
    catalog: &Value,
    source: Option<String>,
) -> Result<ValidateReport> {
    let method = match method_for_request(request, catalog) {
        Ok(method) => method,
        Err(report) => return Ok(report.with_source(source)),
    };
    let schema = catalog_schema::request_schema(catalog, &method)?;
    let validator = jsonschema::validator_for(&schema).map_err(|error| {
        CliError::new(
            "RPC_SCHEMA_INVALID",
            format!("Generated JSON Schema for `{method}` is invalid: {error}"),
        )
    })?;
    let errors: Vec<_> = validator
        .iter_errors(request)
        .map(|error| ValidationFinding {
            source: source.clone(),
            path: json_pointer_path(error.instance_path().as_str()),
            message: error.to_string(),
            schema_path: Some(json_pointer_path(error.schema_path().as_str())),
        })
        .collect();
    Ok(ValidateReport {
        ok: errors.is_empty(),
        source,
        method: Some(method),
        validated: None,
        errors,
    })
}

pub fn validate_samples(samples_root: &Path, catalog: &Value) -> Result<ValidateReport> {
    let mut errors = Vec::new();
    let mut count = 0;
    for path in contract_gen::sample_json_paths(samples_root)? {
        count += 1;
        let source = path.display().to_string();
        let request = read_json_file(&path)?;
        let result = validate_request(&request, catalog, Some(source.clone()))?;
        if !result.ok {
            errors.extend(result.errors);
        }
    }
    Ok(ValidateReport {
        ok: errors.is_empty(),
        source: None,
        method: None,
        validated: Some(count),
        errors,
    })
}

pub fn embedded_catalog_source() -> &'static str {
    include_str!("../resources/kast-skill/references/commands.json")
}

pub fn embedded_catalog() -> Result<Value> {
    Ok(serde_json::from_str(embedded_catalog_source())?)
}

fn load_request(raw: Option<&str>, request_file: Option<&Path>) -> Result<(Value, Option<String>)> {
    if let Some(request_file) = request_file {
        return Ok((
            read_json_file(request_file)?,
            Some(request_file.display().to_string()),
        ));
    }
    let Some(raw) = raw else {
        return Err(CliError::new(
            "CLI_USAGE",
            "Provide a request string, --request-file, or --all-samples.",
        ));
    };
    if let Some(path) = raw.strip_prefix('@') {
        let path = PathBuf::from(path);
        return Ok((read_json_file(&path)?, Some(path.display().to_string())));
    }
    let candidate = PathBuf::from(raw);
    if candidate.is_file() {
        return Ok((
            read_json_file(&candidate)?,
            Some(candidate.display().to_string()),
        ));
    }
    Ok((serde_json::from_str(raw)?, None))
}

fn read_json_file(path: &Path) -> Result<Value> {
    let content = fs::read_to_string(path)?;
    Ok(serde_json::from_str(&content)?)
}

fn method_for_request(
    request: &Value,
    catalog: &Value,
) -> std::result::Result<String, ValidateReport> {
    let Some(object) = request.as_object() else {
        return Err(single_error(
            "$",
            "request must be a JSON object",
            None,
            None,
        ));
    };
    let method = match object.get("method").and_then(Value::as_str) {
        Some(method) => method.to_string(),
        None => {
            return Err(single_error(
                "/method",
                "method must be a string",
                None,
                None,
            ));
        }
    };
    let commands = match catalog.get("commands").and_then(Value::as_object) {
        Some(commands) => commands,
        None => {
            return Err(single_error(
                "/catalog/commands",
                "catalog commands must be an object",
                None,
                Some(method),
            ));
        }
    };
    if !commands.contains_key(&method) {
        return Err(single_error(
            "/method",
            "unknown Kast RPC method",
            None,
            Some(method),
        ));
    }
    Ok(method)
}

fn single_error(
    path: &str,
    message: &str,
    source: Option<String>,
    method: Option<String>,
) -> ValidateReport {
    ValidateReport {
        ok: false,
        source: source.clone(),
        method,
        validated: None,
        errors: vec![ValidationFinding {
            source,
            path: path.to_string(),
            message: message.to_string(),
            schema_path: None,
        }],
    }
}

fn json_pointer_path(path: &str) -> String {
    if path.is_empty() {
        "$".to_string()
    } else {
        path.to_string()
    }
}

fn default_samples_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/kast-skill/references/requests")
}

impl ValidateReport {
    fn with_source(mut self, source: Option<String>) -> Self {
        if self.source.is_none() {
            self.source = source.clone();
        }
        for error in &mut self.errors {
            if error.source.is_none() {
                error.source = source.clone();
            }
        }
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn validates_request_with_generated_schema() {
        let catalog = json!({
            "commands": {
                "symbol/query": {
                    "request": {
                        "fields": {
                            "query": { "type": "string" }
                        },
                        "required": ["query"]
                    }
                }
            }
        });
        let request = json!({
            "jsonrpc": "2.0",
            "method": "symbol/query",
            "params": { "query": "Widget" },
            "id": 1,
        });
        let report = validate_request(&request, &catalog, None).expect("validation");
        assert!(report.ok);
        assert_eq!(report.method.as_deref(), Some("symbol/query"));
    }

    #[test]
    fn rejects_missing_required_params() {
        let catalog = json!({
            "commands": {
                "symbol/query": {
                    "request": {
                        "fields": {
                            "query": { "type": "string" }
                        },
                        "required": ["query"]
                    }
                }
            }
        });
        let request = json!({
            "jsonrpc": "2.0",
            "method": "symbol/query",
            "params": {},
            "id": 1,
        });
        let report = validate_request(&request, &catalog, None).expect("validation");
        assert!(!report.ok);
        assert!(
            report
                .errors
                .iter()
                .any(|error| error.message.contains("query"))
        );
    }
}
