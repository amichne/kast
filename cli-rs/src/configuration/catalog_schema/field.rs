fn fields_to_properties(fields: &Map<String, Value>, context: &str) -> Result<Value> {
    let mut properties = Map::new();
    for (name, field) in fields {
        properties.insert(
            name.clone(),
            field_schema(field, &format!("{context}.{name}"))?,
        );
    }
    Ok(Value::Object(properties))
}

fn field_schema(field: &Value, context: &str) -> Result<Value> {
    let field_type = string_at(field, "type", context)?;
    let nullable = field
        .get("nullable")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let mut schema = Map::new();
    match field_type {
        "array" => {
            schema.insert("type".to_string(), schema_type("array", nullable));
            schema.insert(
                "items".to_string(),
                item_schema(field.get("items"), &format!("{context}.items"))?,
            );
        }
        "object" => {
            schema.insert("type".to_string(), schema_type("object", nullable));
            if let Some(fields) = field.get("fields") {
                let fields = fields.as_object().ok_or_else(|| {
                    CliError::new(
                        "RPC_CATALOG_INVALID",
                        format!("Catalog field `{context}.fields` must be an object."),
                    )
                })?;
                schema.insert(
                    "properties".to_string(),
                    fields_to_properties(fields, context)?,
                );
                let required = request_required(field)?;
                if !required.is_empty() {
                    schema.insert(
                        "required".to_string(),
                        Value::Array(required.into_iter().map(Value::String).collect()),
                    );
                }
                schema.insert("additionalProperties".to_string(), Value::Bool(false));
            } else {
                schema.insert("additionalProperties".to_string(), Value::Bool(true));
            }
        }
        "boolean" | "integer" | "string" => {
            schema.insert("type".to_string(), schema_type(field_type, nullable));
        }
        other => {
            return Err(CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Catalog field `{context}` has unsupported type `{other}`."),
            ));
        }
    }

    if let Some(enum_values) = field.get("enum") {
        let enum_values = enum_values.as_array().ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Catalog field `{context}.enum` must be an array."),
            )
        })?;
        let mut values = enum_values.clone();
        if nullable && !values.iter().any(Value::is_null) {
            values.push(Value::Null);
        }
        schema.insert("enum".to_string(), Value::Array(values));
    }

    Ok(Value::Object(schema))
}

fn item_schema(items: Option<&Value>, context: &str) -> Result<Value> {
    match items {
        None => Ok(open_object_schema()),
        Some(Value::String(item_type)) if item_type == "object" => Ok(open_object_schema()),
        Some(Value::String(item_type)) => Ok(json!({ "type": item_type })),
        Some(value) if value.is_object() => field_schema(value, context),
        Some(_) => Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Catalog field `{context}` must be a primitive item name or object."),
        )),
    }
}

fn open_object_schema() -> Value {
    json!({
        "type": "object",
        "additionalProperties": true,
    })
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

fn schema_type(field_type: &str, nullable: bool) -> Value {
    if nullable {
        json!([field_type, "null"])
    } else {
        Value::String(field_type.to_string())
    }
}

fn value_at<'a>(value: &'a Value, field: &str, context: &str) -> Result<&'a Value> {
    value.get(field).ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Catalog object `{context}` is missing `{field}`."),
        )
    })
}

fn object_at<'a>(value: &'a Value, field: &str, context: &str) -> Result<&'a Map<String, Value>> {
    value_at(value, field, context)?.as_object().ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Catalog field `{context}.{field}` must be an object."),
        )
    })
}

fn string_at<'a>(value: &'a Value, field: &str, context: &str) -> Result<&'a str> {
    value_at(value, field, context)?.as_str().ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Catalog field `{context}.{field}` must be a string."),
        )
    })
}
