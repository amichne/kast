use super::*;

pub(crate) fn decode(output: &Output) -> Value {
    let value: Value = toon_format::decode_default(
        std::str::from_utf8(&output.stdout)
            .expect("UTF-8 output")
            .trim(),
    )
    .unwrap_or_else(|error| {
        panic!(
            "valid TOON: {error}; stdout={}",
            String::from_utf8_lossy(&output.stdout)
        )
    });
    if value["schemaVersion"] == 2 && value["result"].is_object() {
        value["result"].clone()
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
