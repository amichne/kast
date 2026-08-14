use super::*;

pub(crate) fn verified_add_declaration_plan_result(
    plan_id: &str,
    target: &Path,
    declaration: &str,
    generation: u64,
) -> Value {
    json!({
        "planId": plan_id,
        "planVersion": 0,
        "stage": "AWAITING_APPROVAL",
        "operation": "add-declaration",
        "preview": {
            "targetPath": target,
            "proposedDeclaration": declaration,
            "generation": generation,
        },
        "schemaVersion": api_schema_version(),
    })
}

pub(crate) fn verified_add_declaration_receipt(
    plan_id: &str,
    target: &Path,
    publication_generation: u64,
) -> Value {
    json!({
        "outcome": "VERIFIED",
        "planId": plan_id,
        "planVersion": 5,
        "operation": "add-declaration",
        "publication": {
            "generation": publication_generation,
            "workspaceStateIdentity": format!("verified-add-declaration-g{publication_generation}"),
        },
        "identity": {
            "targetPath": target,
            "sourceRange": {"startOffset": 16, "endOffset": 27},
            "packageName": "",
            "declarationName": "Added",
            "declarationKind": "CLASS",
        },
        "postimageSha256": source_sha256(b"class Existing\n\nclass Added\n"),
        "schemaVersion": api_schema_version(),
    })
}
