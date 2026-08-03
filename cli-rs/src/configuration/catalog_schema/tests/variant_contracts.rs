use super::*;
use serde_json::json;

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

#[test]
fn nested_variant_objects_reject_cross_variant_and_unknown_fields() {
    let catalog = json!({
        "commands": {
            "raw/verify-mutation-postcondition": {
                "request": {
                    "fields": {
                        "authority": {
                            "type": "object",
                            "variantDiscriminator": "type",
                            "fields": {
                                "type": {
                                    "type": "string",
                                    "enum": ["RENAME", "ADD_FILE"]
                                }
                            },
                            "variants": {
                                "RENAME": {
                                    "fields": {
                                        "proof": {"type": "object"},
                                        "edits": {"type": "array", "items": "object"}
                                    }
                                },
                                "ADD_FILE": {
                                    "fields": {
                                        "proof": {"type": "object"},
                                        "postimage": {"type": "object"}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    });
    let schema = request_schema(&catalog, "raw/verify-mutation-postcondition")
        .expect("nested variant schema");
    let validator = jsonschema::validator_for(&schema).expect("schema compiles");
    let request = |authority| json!({
        "jsonrpc": "2.0",
        "method": "raw/verify-mutation-postcondition",
        "params": {"authority": authority},
        "id": 1
    });

    assert!(validator.validate(&request(json!({
        "type": "ADD_FILE",
        "proof": {},
        "postimage": {}
    }))).is_ok());
    assert!(validator.validate(&request(json!({
        "type": "ADD_FILE",
        "proof": {},
        "edits": []
    }))).is_err());
    assert!(validator.validate(&request(json!({
        "type": "RENAME",
        "proof": {},
        "edits": [],
        "unexpected": true
    }))).is_err());
}

#[test]
fn nested_variant_objects_reject_unprojected_common_fields() {
    let catalog = json!({
        "commands": {
            "raw/verify-mutation-postcondition": {
                "request": {
                    "fields": {
                        "authority": {
                            "type": "object",
                            "fields": {
                                "type": {
                                    "type": "string",
                                    "enum": ["ADD_FILE"]
                                },
                                "workspaceRoot": {"type": "string"}
                            },
                            "variants": {
                                "ADD_FILE": {
                                    "fields": {
                                        "proof": {"type": "object"}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    });

    let error = request_schema(&catalog, "raw/verify-mutation-postcondition")
        .expect_err("unprojected common field must fail closed");

    assert_eq!(error.code, "RPC_CATALOG_INVALID");
    assert!(error.message.contains("only its discriminator"), "{error:?}");
}
