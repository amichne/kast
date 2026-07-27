pub fn request_schema(catalog: &Value, method: &str) -> Result<Value> {
    let commands = object_at(catalog, "commands", "catalog")?;
    let command = commands.get(method).ok_or_else(|| {
        CliError::new(
            "RPC_METHOD_UNKNOWN",
            format!("Command catalog does not define method `{method}`."),
        )
    })?;
    request_schema_for_command(method, command)
}

pub fn request_schemas(catalog: &Value) -> Result<BTreeMap<String, Value>> {
    object_at(catalog, "commands", "catalog")?
        .iter()
        .map(|(method, command)| {
            request_schema_for_command(method, command).map(|schema| (method.clone(), schema))
        })
        .collect()
}

pub fn request_schema_for_command(method: &str, command: &Value) -> Result<Value> {
    let params = params_schema(command, method)?;
    Ok(json!({
        "$schema": JSON_SCHEMA_DRAFT,
        "title": format!("Kast {method} JSON-RPC request"),
        "type": "object",
        "additionalProperties": false,
        "required": ["jsonrpc", "method", "params", "id"],
        "properties": {
            "jsonrpc": {
                "type": "string",
                "enum": ["2.0"],
            },
            "method": {
                "type": "string",
                "enum": [method],
            },
            "params": params,
            "id": {
                "oneOf": [
                    { "type": "integer" },
                    { "type": "string" },
                    { "type": "null" },
                ],
            },
        },
    }))
}

fn params_schema(command: &Value, method: &str) -> Result<Value> {
    if command.get("variantDiscriminator").is_some()
        && command
            .get("variants")
            .and_then(Value::as_object)
            .is_none_or(Map::is_empty)
    {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant method `{method}` with an explicit variantDiscriminator must define non-empty variants."
            ),
        ));
    }
    match command.get("variants").and_then(Value::as_object) {
        Some(variants) if !variants.is_empty() => {
            let discriminator = variant_discriminator(command, method)?;
            let mut schemas = Vec::with_capacity(variants.len());
            let mut sorted_variants = variants.iter().collect::<Vec<_>>();
            sorted_variants.sort_by_key(|(name, _)| *name);
            for (variant_name, variant_request) in sorted_variants {
                schemas.push(variant_schema(
                    command,
                    &discriminator,
                    variant_name,
                    variant_request,
                    method,
                )?);
            }
            Ok(json!({ "oneOf": schemas }))
        }
        _ => request_object_schema(value_at(command, "request", method)?, method),
    }
}

fn variant_schema(
    command: &Value,
    discriminator: &str,
    variant_name: &str,
    variant_request: &Value,
    method: &str,
) -> Result<Value> {
    let request = value_at(command, "request", method)?;
    let request_fields = object_at(request, "fields", method)?;
    let discriminator_field = request_fields.get(discriminator).ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Variant method `{method}` must define request.fields.{discriminator}."),
        )
    })?;
    let mut discriminator_schema =
        field_schema(discriminator_field, &format!("{method}.{discriminator}"))?;
    let discriminator_schema_object = discriminator_schema.as_object_mut().ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Variant discriminator schema for `{method}` must be an object."),
        )
    })?;
    discriminator_schema_object.remove("enum");
    discriminator_schema_object
        .insert("const".to_string(), Value::String(variant_name.to_string()));
    let mut properties = Map::new();
    properties.insert(discriminator.to_string(), discriminator_schema);

    for (field_name, field) in object_at(variant_request, "fields", variant_name)? {
        if field_name == discriminator {
            return Err(CliError::new(
                "RPC_CATALOG_INVALID",
                format!(
                    "Variant `{variant_name}` for `{method}` must not redeclare `{discriminator}`."
                ),
            ));
        }
        properties.insert(
            field_name.clone(),
            field_schema(field, &format!("{method}.{variant_name}.{field_name}"))?,
        );
    }

    let mut required = vec![Value::String(discriminator.to_string())];
    for field_name in request_required(variant_request)? {
        if field_name != discriminator {
            required.push(Value::String(field_name));
        }
    }

    Ok(json!({
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": false,
    }))
}

pub(crate) fn variant_discriminator(command: &Value, method: &str) -> Result<String> {
    let discriminator = match command.get("variantDiscriminator") {
        None => "type",
        Some(value) => value.as_str().filter(|name| !name.is_empty()).ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!(
                    "Variant method `{method}` must declare variantDiscriminator as a non-empty string."
                ),
            )
        })?,
    };
    let request = value_at(command, "request", method)?;
    let request_fields = object_at(request, "fields", method)?;
    let field = request_fields.get(discriminator).ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Variant method `{method}` must define request.fields.{discriminator}."),
        )
    })?;
    if field.get("type").and_then(Value::as_str) != Some("string") {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant discriminator `{method}.request.fields.{discriminator}` must have type string."
            ),
        ));
    }
    let enum_values = field.get("enum").and_then(Value::as_array).ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant discriminator `{method}.request.fields.{discriminator}` must define an enum."
            ),
        )
    })?;
    let variants = command
        .get("variants")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Variant method `{method}` must define variants."),
            )
        })?;
    let enum_names = enum_values
        .iter()
        .map(|value| value.as_str())
        .collect::<Option<std::collections::BTreeSet<_>>>()
        .ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!(
                    "Variant discriminator `{method}.request.fields.{discriminator}.enum` must contain only strings."
                ),
            )
        })?;
    let variant_names = variants.keys().map(String::as_str).collect();
    if enum_names.len() != enum_values.len()
        || enum_names.len() != variants.len()
        || enum_names != variant_names
    {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!(
                "Variant discriminator `{method}.request.fields.{discriminator}.enum` must name every variant exactly once."
            ),
        ));
    }

    Ok(discriminator.to_string())
}

fn request_object_schema(request: &Value, context: &str) -> Result<Value> {
    let fields = object_at(request, "fields", context)?;
    let required = request_required(request)?;
    let exclusive_required = request_exclusive_required(request, context)?;
    let mut schema = Map::new();
    schema.insert("type".to_string(), Value::String("object".to_string()));
    schema.insert(
        "properties".to_string(),
        fields_to_properties(fields, context)?,
    );
    if !required.is_empty() {
        schema.insert(
            "required".to_string(),
            Value::Array(required.into_iter().map(Value::String).collect()),
        );
    }
    if !exclusive_required.is_empty() {
        schema.insert(
            "oneOf".to_string(),
            Value::Array(
                exclusive_required
                    .into_iter()
                    .map(|field| json!({ "required": [field] }))
                    .collect(),
            ),
        );
    }
    schema.insert("additionalProperties".to_string(), Value::Bool(false));
    Ok(Value::Object(schema))
}

pub(crate) fn request_exclusive_required(request: &Value, context: &str) -> Result<Vec<String>> {
    let Some(values) = request.get("exclusiveRequired") else {
        return Ok(Vec::new());
    };
    let values = values.as_array().ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Catalog exclusiveRequired for `{context}` must be an array."),
        )
    })?;
    if values.len() < 2 {
        return Err(CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Catalog exclusiveRequired for `{context}` must name at least two fields."),
        ));
    }
    let fields = object_at(request, "fields", context)?;
    let mut names = Vec::with_capacity(values.len());
    for value in values {
        let name = value.as_str().filter(|name| !name.is_empty()).ok_or_else(|| {
            CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Catalog exclusiveRequired entries for `{context}` must be non-empty strings."),
            )
        })?;
        if !fields.contains_key(name) {
            return Err(CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Catalog exclusiveRequired field `{context}.{name}` is not declared."),
            ));
        }
        if names.iter().any(|existing| existing == name) {
            return Err(CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Catalog exclusiveRequired field `{context}.{name}` is duplicated."),
            ));
        }
        names.push(name.to_string());
    }
    Ok(names)
}
