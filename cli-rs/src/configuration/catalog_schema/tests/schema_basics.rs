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
