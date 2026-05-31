use crate::error::{CliError, Result};
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;

pub fn raw(socket_path: &Path, request: &str) -> Result<String> {
    let mut stream = UnixStream::connect(socket_path).map_err(|error| {
        CliError::new(
            "DAEMON_UNREACHABLE",
            format!(
                "Failed to reach daemon at {}: {error}",
                socket_path.display()
            ),
        )
    })?;
    stream.write_all(request.as_bytes())?;
    stream.write_all(b"\n")?;
    stream.flush()?;
    let mut reader = BufReader::new(stream);
    let mut response = String::new();
    let read = reader.read_line(&mut response)?;
    if read == 0 {
        return Err(CliError::new(
            "RPC_RESPONSE_MISSING",
            "The daemon closed the socket without returning a response",
        ));
    }
    Ok(response.trim_end_matches(['\r', '\n']).to_string())
}

pub fn request<T: DeserializeOwned>(socket_path: &Path, method: &str, params: Value) -> Result<T> {
    let request = json!({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": 1
    });
    let response = raw(socket_path, &serde_json::to_string(&request)?)?;
    let value: Value = serde_json::from_str(&response)?;
    if let Some(error) = value.get("error") {
        let code = error
            .get("data")
            .and_then(|data| data.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("RPC_ERROR");
        let message = error
            .get("data")
            .and_then(|data| data.get("message"))
            .or_else(|| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("JSON-RPC request failed");
        return Err(CliError::new("RPC_ERROR", format!("{code}: {message}")));
    }
    let result = value.get("result").ok_or_else(|| {
        CliError::new(
            "RPC_RESPONSE_INVALID",
            "JSON-RPC response did not include a result field",
        )
    })?;
    Ok(serde_json::from_value(result.clone())?)
}
