use crate::error::{CliError, Result};
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;

const JSON_SCHEMA_DRAFT: &str = "https://json-schema.org/draft/2020-12/schema";

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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn nullable_enum_includes_null_as_an_allowed_value() {
        let catalog = json!({
            "commands": {
                "symbol/example": {
                    "request": {
                        "fields": {
                            "kind": {
                                "type": "string",
                                "enum": ["class", "function"],
                                "nullable": true
                            }
                        }
                    }
                }
            }
        });
        let schema = request_schema(&catalog, "symbol/example").expect("schema");
        let validator = jsonschema::validator_for(&schema).expect("schema compiles");
        let valid = json!({
            "jsonrpc": "2.0",
            "method": "symbol/example",
            "params": { "kind": null },
            "id": 1,
        });
        assert!(
            validator.validate(&valid).is_ok(),
            "nullable enum fields must accept null"
        );
    }

    #[test]
    fn exclusive_required_fields_accept_exactly_one_alternative() {
        let catalog = json!({
            "commands": {
                "symbol/references": {
                    "request": {
                        "fields": {
                            "selectorHandle": { "type": "string", "optional": true },
                            "selector": { "type": "object", "optional": true }
                        },
                        "exclusiveRequired": ["selectorHandle", "selector"]
                    }
                }
            }
        });
        let schema = request_schema(&catalog, "symbol/references").expect("schema");
        let validator = jsonschema::validator_for(&schema).expect("schema compiles");
        let request = |params| {
            json!({
                "jsonrpc": "2.0",
                "method": "symbol/references",
                "params": params,
                "id": 1,
            })
        };

        assert!(
            validator
                .validate(&request(json!({ "selectorHandle": "ksh1.opaque" })))
                .is_ok()
        );
        assert!(
            validator
                .validate(&request(json!({ "selector": {} })))
                .is_ok()
        );
        assert!(validator.validate(&request(json!({}))).is_err());
        assert!(
            validator
                .validate(&request(json!({
                    "selectorHandle": "ksh1.opaque",
                    "selector": {}
                })))
                .is_err()
        );
    }

    #[test]
    fn variants_are_discriminated_with_const_type_values() {
        let catalog = json!({
            "commands": {
                "symbol/rename": {
                    "request": {
                        "fields": {
                            "type": {
                                "type": "string",
                                "enum": ["BY_SYMBOL", "BY_OFFSET"]
                            }
                        },
                        "required": ["type"]
                    },
                    "variants": {
                        "BY_SYMBOL": {
                            "fields": {
                                "symbol": { "type": "string" },
                                "newName": { "type": "string" }
                            },
                            "required": ["symbol", "newName"]
                        },
                        "BY_OFFSET": {
                            "fields": {
                                "filePath": { "type": "string" },
                                "offset": { "type": "integer" }
                            },
                            "required": ["filePath", "offset"]
                        }
                    }
                }
            }
        });
        let schema = request_schema(&catalog, "symbol/rename").expect("schema");
        let validator = jsonschema::validator_for(&schema).expect("schema compiles");
        let valid = json!({
            "jsonrpc": "2.0",
            "method": "symbol/rename",
            "params": {
                "type": "BY_SYMBOL",
                "symbol": "Widget",
                "newName": "RenamedWidget"
            },
            "id": 1,
        });
        let invalid = json!({
            "jsonrpc": "2.0",
            "method": "symbol/rename",
            "params": {
                "type": "BY_SYMBOL",
                "filePath": "/tmp/Widget.kt",
                "offset": 12
            },
            "id": 1,
        });
        assert!(validator.validate(&valid).is_ok());
        assert!(validator.validate(&invalid).is_err());
    }

    #[test]
    fn variants_can_declare_an_action_discriminator() {
        let catalog = json!({
            "commands": {
                "raw/workspace-files-continuation": {
                    "variantDiscriminator": "action",
                    "request": {
                        "fields": {
                            "action": {
                                "type": "string",
                                "enum": ["ISSUE", "CONSUME"]
                            }
                        },
                        "required": ["action"]
                    },
                    "variants": {
                        "ISSUE": {
                            "fields": {
                                "state": { "type": "object" }
                            },
                            "required": ["state"]
                        },
                        "CONSUME": {
                            "fields": {
                                "pageToken": { "type": "string" }
                            },
                            "required": ["pageToken"]
                        }
                    }
                }
            }
        });
        let schema = request_schema(&catalog, "raw/workspace-files-continuation")
            .expect("action-discriminated schema");
        let validator = jsonschema::validator_for(&schema).expect("schema compiles");
        let valid = json!({
            "jsonrpc": "2.0",
            "method": "raw/workspace-files-continuation",
            "params": {
                "action": "CONSUME",
                "pageToken": "00000000-0000-4000-8000-000000000338"
            },
            "id": 1,
        });
        let invalid = json!({
            "jsonrpc": "2.0",
            "method": "raw/workspace-files-continuation",
            "params": {
                "type": "CONSUME",
                "pageToken": "00000000-0000-4000-8000-000000000338"
            },
            "id": 1,
        });

        assert!(validator.validate(&valid).is_ok());
        assert!(validator.validate(&invalid).is_err());
    }

    #[test]
    fn explicit_variant_discriminator_must_name_a_declared_field() {
        let catalog = json!({
            "commands": {
                "raw/workspace-files-continuation": {
                    "variantDiscriminator": "action",
                    "request": {
                        "fields": {
                            "type": {
                                "type": "string",
                                "enum": ["ISSUE"]
                            }
                        }
                    },
                    "variants": {
                        "ISSUE": {
                            "fields": {}
                        }
                    }
                }
            }
        });

        let error = request_schema(&catalog, "raw/workspace-files-continuation")
            .expect_err("missing action field must fail");

        assert_eq!(error.code, "RPC_CATALOG_INVALID");
        assert!(error.message.contains("request.fields.action"));
    }

    #[test]
    fn explicit_variant_discriminator_must_be_a_non_empty_string() {
        for malformed in [json!(""), json!(7)] {
            let catalog = json!({
                "commands": {
                    "raw/workspace-files-continuation": {
                        "variantDiscriminator": malformed,
                        "request": {
                            "fields": {
                                "action": {
                                    "type": "string",
                                    "enum": ["ISSUE"]
                                }
                            }
                        },
                        "variants": {
                            "ISSUE": {
                                "fields": {}
                            }
                        }
                    }
                }
            });

            let error = request_schema(&catalog, "raw/workspace-files-continuation")
                .expect_err("malformed discriminator must fail");

            assert_eq!(error.code, "RPC_CATALOG_INVALID");
            assert!(error.message.contains("non-empty string"));
        }
    }

    #[test]
    fn explicit_variant_discriminator_requires_a_non_empty_variant_map() {
        for (description, variants) in [
            ("missing", None),
            ("empty", Some(json!({}))),
            ("non-object", Some(json!([]))),
        ] {
            let mut command = json!({
                "variantDiscriminator": "action",
                "request": {
                    "fields": {
                        "action": {
                            "type": "string",
                            "enum": ["ISSUE"]
                        }
                    },
                    "required": ["action"]
                }
            });
            if let Some(variants) = variants {
                command["variants"] = variants;
            }
            let catalog = json!({
                "commands": {
                    "raw/workspace-files-continuation": command
                }
            });

            let failure = format!("{description} variants must fail closed");
            let error =
                request_schema(&catalog, "raw/workspace-files-continuation").expect_err(&failure);

            assert_eq!(error.code, "RPC_CATALOG_INVALID", "{description}");
            assert!(error.message.contains("non-empty variants"), "{error:?}");
        }
    }

    #[test]
    fn variant_discriminator_enum_rejects_duplicate_variant_names() {
        let catalog = json!({
            "commands": {
                "raw/workspace-files-continuation": {
                    "variantDiscriminator": "action",
                    "request": {
                        "fields": {
                            "action": {
                                "type": "string",
                                "enum": ["ISSUE", "ISSUE"]
                            }
                        },
                        "required": ["action"]
                    },
                    "variants": {
                        "ISSUE": {
                            "fields": {}
                        }
                    }
                }
            }
        });

        let error = request_schema(&catalog, "raw/workspace-files-continuation")
            .expect_err("duplicate variant enum entries must fail closed");

        assert_eq!(error.code, "RPC_CATALOG_INVALID");
        assert!(error.message.contains("exactly once"), "{error:?}");
    }

    #[test]
    fn variant_schema_order_is_canonical() {
        let catalog = json!({
            "commands": {
                "symbol/rename": {
                    "request": {
                        "fields": {
                            "type": {
                                "type": "string",
                                "enum": ["BY_SYMBOL", "BY_OFFSET"]
                            }
                        },
                        "required": ["type"]
                    },
                    "variants": {
                        "BY_SYMBOL": {
                            "fields": {
                                "symbol": { "type": "string" }
                            },
                            "required": ["symbol"]
                        },
                        "BY_OFFSET": {
                            "fields": {
                                "filePath": { "type": "string" }
                            },
                            "required": ["filePath"]
                        }
                    }
                }
            }
        });
        let schema = request_schema(&catalog, "symbol/rename").expect("schema");
        let variants = schema["properties"]["params"]["oneOf"]
            .as_array()
            .expect("oneOf variants");
        let first_type = &variants[0]["properties"]["type"]["const"];

        assert_eq!(first_type, "BY_OFFSET");
    }
}
