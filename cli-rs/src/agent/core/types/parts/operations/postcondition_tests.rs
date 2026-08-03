    use super::*;

    fn identity() -> Value {
        json!({
            "fqName": "sample.target",
            "kind": "FUNCTION",
            "declarationFile": "/workspace/src/Target.kt",
            "declarationStartOffset": 4
        })
    }

    fn signature() -> Value {
        json!({
            "type": "function",
            "name": "target",
            "receiverType": null,
            "contextReceiverTypes": [],
            "typeParameters": [],
            "valueParameters": [],
            "returnType": "kotlin.Unit",
            "visibility": "PUBLIC",
            "modality": "FINAL",
            "hasStableParameterNames": true,
            "suspend": false,
            "operator": false,
            "inline": false,
            "override": false,
            "infix": false,
            "static": false,
            "tailrec": false,
            "external": false,
            "expect": false,
            "actual": false
        })
    }

    fn replacement_dimensions() -> Value {
        json!([
            "EXACT_TARGET_IDENTITY",
            "SUPPORTED_TARGET_KIND",
            "SINGLE_SUPPORTED_PROPOSED_DECLARATION",
            "COMPILER_SIGNATURE_EQUAL",
            "PROPOSED_PSI_TRAVERSAL_EXHAUSTIVE",
            "EVERY_REFERENCE_COMPILER_RESOLVED",
            "EVERY_REFERENCE_TARGET_MATCHED",
            "EVERY_CALL_EXACT",
            "NO_UNSUPPORTED_REFERENCE_KIND",
            "EXACT_OUTBOUND_CARDINALITY",
            "SOURCE_CONTEXT_HASH_BOUND",
            "SEMANTIC_GENERATION_UNCHANGED"
        ])
    }

    fn owner() -> Value {
        json!({
            "sourceRoot": "/workspace/src",
            "ideaModuleName": "root.main",
            "gradleBuildRoot": "/workspace",
            "gradleProjectPath": ":",
            "sourceSetName": "main"
        })
    }

    fn declaration() -> Value {
        json!({
            "packageIdentity": {"type": "ROOT"},
            "name": "Added",
            "kind": "CLASS",
            "relativeRange": {"startOffset": 0, "endOffset": 5},
            "collisionSignature": "1".repeat(64)
        })
    }

    fn valid_variants() -> Vec<Value> {
        vec![
            json!({
                "type": "RENAME",
                "resultingTarget": identity(),
                "evidence": {
                    "type": "COMPLETE",
                    "cardinality": {"type": "EXACT", "totalCount": 0},
                    "coverage": {
                        "type": "COMPLETE",
                        "identity": "COMPLETE",
                        "projectScope": "COMPLETE",
                        "sourceSetScope": "COMPLETE",
                        "indexFreshness": "COMPLETE",
                        "backend": "COMPLETE",
                        "requestedFamily": "COMPLETE",
                        "limitations": []
                    }
                },
                "occurrences": []
            }),
            json!({
                "type": "REPLACEMENT",
                "resultingTarget": identity(),
                "sourceRange": {
                    "filePath": "/workspace/src/Target.kt",
                    "startOffset": 0,
                    "endOffset": 8,
                    "startLine": 1,
                    "startColumn": 1,
                    "preview": "fun x()"
                },
                "signature": signature(),
                "outboundEvidence": {
                    "type": "complete",
                    "cardinality": {"type": "EXACT", "totalCount": 0},
                    "dimensions": replacement_dimensions()
                },
                "outboundReferences": []
            }),
            json!({
                "type": "ADD_FILE",
                "owner": owner(),
                "packageIdentity": {"type": "ROOT"},
                "declarations": [declaration()],
                "outboundEvidence": {"cardinality": 0, "occurrences": []}
            }),
            json!({
                "type": "ADD_DECLARATION",
                "owner": owner(),
                "packageIdentity": {"type": "ROOT"},
                "declaration": declaration(),
                "outboundEvidence": {"cardinality": 0, "occurrences": []}
            }),
        ]
    }

    #[test]
    fn all_postcondition_variants_reject_malformed_nested_evidence() {
        for mut value in valid_variants() {
            match value["type"].as_str().expect("variant") {
                "RENAME" | "REPLACEMENT" => {
                    value["resultingTarget"]["unexpected"] = json!(true)
                }
                "ADD_FILE" | "ADD_DECLARATION" => {
                    value["owner"]["unexpected"] = json!(true)
                }
                _ => unreachable!(),
            }
            assert!(
                serde_json::from_value::<AgentMutationPostconditionEvidence>(value).is_err()
            );
        }
    }

    #[test]
    fn all_postcondition_variants_retain_semantic_substitutions_in_typed_equality() {
        for value in valid_variants() {
            let expected: AgentMutationPostconditionEvidence =
                serde_json::from_value(value.clone()).expect("valid typed evidence");
            let mut substituted = value;
            match substituted["type"].as_str().expect("variant") {
                "RENAME" => substituted["resultingTarget"]["fqName"] = json!("sample.other"),
                "REPLACEMENT" => {
                    substituted["signature"]["returnType"] = json!("kotlin.String")
                }
                "ADD_FILE" => substituted["owner"]["sourceSetName"] = json!("test"),
                "ADD_DECLARATION" => substituted["declaration"]["name"] = json!("Other"),
                _ => unreachable!(),
            }
            let substituted = serde_json::from_value(substituted)
                .expect("substitution remains structurally valid");
            assert_ne!(expected, substituted);
        }
    }
