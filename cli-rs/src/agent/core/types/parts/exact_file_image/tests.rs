use super::*;

#[test]
fn exact_file_image_builds_closed_forward_restore_cas_transports() {
    let preimage = b"value\r\n";
    let postimage = b"changed\r\n";
    let image: AgentExactFileImage = serde_json::from_value(json!({
        "filePath": "/workspace/Value.kt",
        "preimage": {
            "contentBase64": STANDARD_BASE64.encode(preimage),
            "sha256": exact_file_sha256(preimage)
        },
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(postimage),
            "sha256": exact_file_sha256(postimage)
        }
    }))
    .expect("typed exact file image");

    let forward = image.forward_cas_request();
    assert_eq!(
        serde_json::to_value(&forward).expect("forward request"),
        json!({
            "filePath": "/workspace/Value.kt",
            "expectedCurrentSha256": exact_file_sha256(preimage),
            "contentBase64": STANDARD_BASE64.encode(postimage),
            "expectedResultSha256": exact_file_sha256(postimage)
        })
    );
    let response: AgentExactFileImageCasResponse = serde_json::from_value(json!({
        "filePath": "/workspace/Value.kt",
        "status": "COMMITTED",
        "previousSha256": exact_file_sha256(preimage),
        "resultSha256": exact_file_sha256(postimage),
        "schemaVersion": SCHEMA_VERSION
    }))
    .expect("closed CAS response");
    response.validate_for(&forward).expect("matching response");

    assert_eq!(
        serde_json::to_value(image.restore_cas_request()).expect("restore request"),
        json!({
            "filePath": "/workspace/Value.kt",
            "expectedCurrentSha256": exact_file_sha256(postimage),
            "contentBase64": STANDARD_BASE64.encode(preimage),
            "expectedResultSha256": exact_file_sha256(preimage)
        })
    );
}

#[test]
fn exact_file_image_rejects_noncanonical_absolute_path_text() {
    let image: AgentExactFileImage = serde_json::from_value(json!({
        "filePath": "/workspace//Value.kt",
        "preimage": {
            "contentBase64": STANDARD_BASE64.encode(b"before"),
            "sha256": exact_file_sha256(b"before")
        },
        "postimage": {
            "contentBase64": STANDARD_BASE64.encode(b"after"),
            "sha256": exact_file_sha256(b"after")
        }
    }))
    .expect("closed wire shape");

    assert_eq!(
        image.decode().expect_err("noncanonical path rejected"),
        "exact file image path was not normalized and absolute"
    );
}
