#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn generated_files_include_yaml_samples_and_schema() {
        let catalog = json!({
            "commands": {
                "symbol/example": {
                    "method": "symbol/example",
                    "category": "symbol",
                    "request": {
                        "fields": {
                            "query": { "type": "string" }
                        },
                        "required": ["query"]
                    }
                }
            }
        });
        let files = generated_files_from_catalog(
            &catalog,
            Path::new("/tmp/commands.yaml"),
            Path::new("/tmp/requests"),
        )
        .expect("generated files");
        assert!(files.contains_key(Path::new("/tmp/commands.yaml")));
        assert!(files.contains_key(Path::new("/tmp/requests/symbol/example/minimal.json")));
        assert!(files.contains_key(Path::new("/tmp/requests/symbol/example/maximal.json")));
        assert!(files.contains_key(Path::new(
            "/tmp/requests/symbol/example/request.schema.json"
        )));
    }

    #[test]
    fn generated_samples_choose_one_exclusive_required_field() {
        let catalog = json!({
            "commands": {
                "symbol/references": {
                    "method": "symbol/references",
                    "category": "symbol",
                    "request": {
                        "fields": {
                            "workspaceRoot": { "type": "string", "optional": true },
                            "selectorHandle": {
                                "type": "string",
                                "optional": true,
                                "sample": "ksh1.opaque"
                            },
                            "selector": {
                                "type": "object",
                                "optional": true,
                                "fields": {
                                    "fqName": { "type": "string" }
                                },
                                "required": ["fqName"]
                            }
                        },
                        "exclusiveRequired": ["selectorHandle", "selector"]
                    }
                }
            }
        });
        let files = generated_files_from_catalog(
            &catalog,
            Path::new("/tmp/commands.yaml"),
            Path::new("/tmp/requests"),
        )
        .expect("generated files");
        let parse = |name| {
            serde_json::from_str::<Value>(
                files
                    .get(Path::new(name))
                    .expect("generated request sample"),
            )
            .expect("request sample JSON")
        };
        let minimal = parse("/tmp/requests/symbol/references/minimal.json");
        let maximal = parse("/tmp/requests/symbol/references/maximal.json");

        assert_eq!(minimal["params"]["selectorHandle"], "ksh1.opaque");
        assert!(minimal["params"].get("selector").is_none());
        assert!(maximal["params"].get("selectorHandle").is_none());
        assert_eq!(
            maximal["params"]["selector"]["fqName"],
            "com.example.Widget"
        );
    }

    #[test]
    fn generated_files_keep_canonical_object_order() {
        let catalog = json!({
            "version": "dev",
            "commands": {
                "symbol/example": {
                    "summary": "Example command",
                    "method": "symbol/example",
                    "request": {
                        "required": ["query"],
                        "fields": {
                            "query": { "type": "string" }
                        }
                    },
                    "category": "symbol"
                }
            },
            "$schema": "./commands.schema.json",
            "categories": {
                "symbol": ["symbol/example"]
            }
        });
        let files = generated_files_from_catalog(
            &catalog,
            Path::new("/tmp/commands.yaml"),
            Path::new("/tmp/requests"),
        )
        .expect("generated files");

        let yaml = files
            .get(Path::new("/tmp/commands.yaml"))
            .expect("commands yaml");
        assert!(
            yaml.starts_with("$schema: ./commands.schema.json\ncategories:\n"),
            "{yaml}"
        );

        let minimal = files
            .get(Path::new("/tmp/requests/symbol/example/minimal.json"))
            .expect("minimal sample");
        assert_eq!(
            minimal,
            "{\n  \"id\": 1,\n  \"jsonrpc\": \"2.0\",\n  \"method\": \"symbol/example\",\n  \"params\": {\n    \"query\": \"Widget\"\n  }\n}\n"
        );
    }

    #[test]
    fn generated_samples_use_field_sample_override() {
        let catalog = json!({
            "commands": {
                "symbol/example": {
                    "method": "symbol/example",
                    "category": "symbol",
                    "request": {
                        "fields": {
                            "placement": {
                                "type": "object",
                                "sample": {
                                    "scope": {
                                        "type": "NAMED_SCOPE",
                                        "insideScope": "com.example.Widget"
                                    },
                                    "anchor": {
                                        "type": "AT_ANCHOR",
                                        "anchor": "body-end"
                                    }
                                },
                                "fields": {
                                    "scope": { "type": "object" },
                                    "anchor": { "type": "object" }
                                }
                            }
                        },
                        "required": ["placement"]
                    }
                }
            }
        });
        let files = generated_files_from_catalog(
            &catalog,
            Path::new("/tmp/commands.yaml"),
            Path::new("/tmp/requests"),
        )
        .expect("generated files");
        let minimal = files
            .get(Path::new("/tmp/requests/symbol/example/minimal.json"))
            .expect("minimal sample");

        assert!(
            minimal.contains("\"insideScope\": \"com.example.Widget\""),
            "{minimal}"
        );
        assert!(minimal.contains("\"anchor\": \"body-end\""), "{minimal}");
    }

    #[test]
    fn generated_variant_samples_use_the_declared_action_discriminator() {
        let catalog = json!({
            "commands": {
                "raw/workspace-files-continuation": {
                    "method": "raw/workspace-files-continuation",
                    "category": "raw",
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
        let files = generated_files_from_catalog(
            &catalog,
            Path::new("/tmp/commands.yaml"),
            Path::new("/tmp/requests"),
        )
        .expect("generated files");
        let issued: Value = serde_json::from_str(
            files
                .get(Path::new(
                    "/tmp/requests/raw/workspace-files-continuation/ISSUE/minimal.json",
                ))
                .expect("ISSUE sample"),
        )
        .expect("ISSUE JSON");
        let consumed: Value = serde_json::from_str(
            files
                .get(Path::new(
                    "/tmp/requests/raw/workspace-files-continuation/CONSUME/minimal.json",
                ))
                .expect("CONSUME sample"),
        )
        .expect("CONSUME JSON");

        assert_eq!(issued["params"]["action"], "ISSUE");
        assert_eq!(consumed["params"]["action"], "CONSUME");
        assert!(issued["params"].get("type").is_none());
        assert!(consumed["params"].get("type").is_none());
    }

    #[test]
    fn sample_path_filter_excludes_generated_schemas() {
        assert!(is_sample_json_path(Path::new("minimal.json")));
        assert!(is_sample_json_path(Path::new("maximal.json")));
        assert!(!is_sample_json_path(Path::new("request.schema.json")));
    }
}
