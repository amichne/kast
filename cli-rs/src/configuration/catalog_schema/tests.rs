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
