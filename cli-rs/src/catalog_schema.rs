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
    match command.get("variants").and_then(Value::as_object) {
        Some(variants) if !variants.is_empty() => {
            let mut schemas = Vec::with_capacity(variants.len());
            let mut sorted_variants = variants.iter().collect::<Vec<_>>();
            sorted_variants.sort_by_key(|(name, _)| *name);
            for (variant_name, variant_request) in sorted_variants {
                schemas.push(variant_schema(
                    command,
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
    variant_name: &str,
    variant_request: &Value,
    method: &str,
) -> Result<Value> {
    let request = value_at(command, "request", method)?;
    let request_fields = object_at(request, "fields", method)?;
    let type_field = request_fields.get("type").ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Variant method `{method}` must define request.fields.type."),
        )
    })?;
    let mut properties = Map::new();
    let mut type_schema = field_schema(type_field, &format!("{method}.type"))?;
    let type_schema_object = type_schema.as_object_mut().ok_or_else(|| {
        CliError::new(
            "RPC_CATALOG_INVALID",
            format!("Variant type schema for `{method}` must be an object."),
        )
    })?;
    type_schema_object.remove("enum");
    type_schema_object.insert("const".to_string(), Value::String(variant_name.to_string()));
    properties.insert("type".to_string(), type_schema);

    for (field_name, field) in object_at(variant_request, "fields", variant_name)? {
        if field_name == "type" {
            return Err(CliError::new(
                "RPC_CATALOG_INVALID",
                format!("Variant `{variant_name}` for `{method}` must not redeclare `type`."),
            ));
        }
        properties.insert(
            field_name.clone(),
            field_schema(field, &format!("{method}.{variant_name}.{field_name}"))?,
        );
    }

    let mut required = vec![Value::String("type".to_string())];
    for field_name in request_required(variant_request)? {
        if field_name != "type" {
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

fn request_object_schema(request: &Value, context: &str) -> Result<Value> {
    let fields = object_at(request, "fields", context)?;
    let required = request_required(request)?;
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
    schema.insert("additionalProperties".to_string(), Value::Bool(false));
    Ok(Value::Object(schema))
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
