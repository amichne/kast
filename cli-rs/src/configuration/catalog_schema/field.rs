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
            if field.get("variants").is_some() {
                return nested_variant_object_schema(field, context, nullable);
            }
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

fn nested_variant_object_schema(
    field: &Value,
    context: &str,
    nullable: bool,
) -> Result<Value> {
    if nullable {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Variant object field `{context}` cannot be nullable."),
        ));
    }
    let discriminator = field
        .get("variantDiscriminator")
        .and_then(Value::as_str)
        .unwrap_or("type");
    let base_fields = field
        .get("fields")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Variant object field `{context}` needs base fields."),
            )
        })?;
    let discriminator_field = base_fields.get(discriminator).ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant object field `{context}` must declare its `{discriminator}` discriminator."
            ),
        )
    })?;
    if base_fields.keys().any(|name| name != discriminator) {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant object field `{context}` may declare only its discriminator as a base field."
            ),
        ));
    }
    let enum_values = discriminator_field
        .get("enum")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!(
                    "Variant object discriminator `{context}.{discriminator}` needs an enum."
                ),
            )
        })?;
    let variants = field
        .get("variants")
        .and_then(Value::as_object)
        .filter(|variants| !variants.is_empty())
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Variant object field `{context}` needs non-empty variants."),
            )
        })?;
    let enum_names = enum_values
        .iter()
        .map(Value::as_str)
        .collect::<Option<BTreeSet<_>>>()
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Variant object discriminator `{context}` enum was not strings."),
            )
        })?;
    let variant_names = variants.keys().map(String::as_str).collect::<BTreeSet<_>>();
    if enum_names != variant_names || enum_values.len() != variants.len() {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant object discriminator `{context}` must name every variant exactly once."
            ),
        ));
    }
    let mut schemas = Vec::with_capacity(variants.len());
    let mut sorted = variants.iter().collect::<Vec<_>>();
    sorted.sort_by_key(|(name, _)| *name);
    for (variant_name, variant_request) in sorted {
        let mut properties = Map::new();
        let mut discriminator_schema = field_schema(
            discriminator_field,
            &format!("{context}.{discriminator}"),
        )?
        .as_object()
        .cloned()
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Variant object discriminator `{context}` was not a schema object."),
            )
        })?;
        discriminator_schema.remove("enum");
        discriminator_schema.insert("const".to_string(), Value::String(variant_name.clone()));
        properties.insert(discriminator.to_string(), Value::Object(discriminator_schema));
        for (name, variant_field) in object_at(variant_request, "fields", variant_name)? {
            if name == discriminator || base_fields.contains_key(name) {
                return Err(CliError::new(
                    "RPC_CATALOG_INVALID",
                    format!(
                        "Nested variant `{context}.{variant_name}` redeclared base field `{name}`."
                    ),
                ));
            }
            properties.insert(
                name.clone(),
                field_schema(variant_field, &format!("{context}.{variant_name}.{name}"))?,
            );
        }
        let mut required = vec![Value::String(discriminator.to_string())];
        required.extend(
            request_required(variant_request)?
                .into_iter()
                .map(Value::String),
        );
        schemas.push(json!({
            "type": "object",
            "properties": properties,
            "required": required,
            "additionalProperties": false,
        }));
    }
    Ok(json!({"oneOf": schemas}))
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
