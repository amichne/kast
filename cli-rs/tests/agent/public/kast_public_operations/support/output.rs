use super::*;

pub(crate) fn decode_envelope(output: &Output) -> Value {
    toon_format::decode_default(
        std::str::from_utf8(&output.stdout)
            .expect("UTF-8 output")
            .trim(),
    )
    .unwrap_or_else(|error| {
        panic!(
            "valid TOON: {error}; stdout={}",
            String::from_utf8_lossy(&output.stdout)
        )
    })
}

pub(crate) fn decode(output: &Output) -> Value {
    let value = decode_envelope(output);
    if value["schemaVersion"] == 3 && value["result"].is_object() {
        if value["result"]["type"] == "rejected" && value["result"]["failure"].is_object() {
            value["result"]["failure"].clone()
        } else {
            value["result"].clone()
        }
    } else {
        value
    }
}

pub(crate) fn assert_selector_forwarding(requests: &[Value], selector: &str, family: &str) {
    let selector_requests = requests
        .iter()
        .filter(|request| request["method"] == "selector/identity")
        .collect::<Vec<_>>();
    assert_eq!(selector_requests.len(), 2, "{requests:#?}");
    assert!(selector_requests.iter().all(|request| {
        request["params"]["selectorHandle"] == selector && request["params"]["family"] == family
    }));
}
