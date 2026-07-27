fn sample_request(request: &Value, maximal: bool) -> Result<Map<String, Value>> {
    let fields = request
        .get("fields")
        .and_then(Value::as_object)
        .ok_or_else(|| CliError::new("RPC_CATALOG_INVALID", "Request fields must be an object."))?;
    let required = request_required(request)?;
    let exclusive_required = catalog_schema::request_exclusive_required(request, "request")?;
    let selected_exclusive = if maximal {
        exclusive_required.last()
    } else {
        exclusive_required.first()
    };
    let mut payload = Map::new();
    for (name, field) in fields {
        let belongs_to_exclusive_group = exclusive_required.iter().any(|field| field == name);
        if selected_exclusive == Some(name)
            || (!belongs_to_exclusive_group && maximal)
            || required.iter().any(|required_name| required_name == name)
        {
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
    if let Some(sample) = field.get("sample") {
        return Ok(sample.clone());
    }

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
