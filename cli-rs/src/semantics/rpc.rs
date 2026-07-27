use crate::error::{CliError, Result};
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::time::Duration;

pub fn raw(socket_path: &Path, request: &str) -> Result<String> {
    raw_with_close_wait(socket_path, request, None)
}

pub fn raw_wait_for_close(
    socket_path: &Path,
    request: &str,
    close_wait_timeout: Duration,
) -> Result<String> {
    raw_with_close_wait(socket_path, request, Some(close_wait_timeout))
}

fn raw_with_close_wait(
    socket_path: &Path,
    request: &str,
    close_wait_timeout: Option<Duration>,
) -> Result<String> {
    let mut stream = UnixStream::connect(socket_path).map_err(|error| {
        CliError::new(
            "DAEMON_UNREACHABLE",
            format!(
                "Failed to reach daemon at {}: {error}",
                socket_path.display()
            ),
        )
    })?;
    if let Some(timeout) = close_wait_timeout {
        stream.set_read_timeout(Some(timeout))?;
    }
    stream.write_all(request.as_bytes())?;
    stream.write_all(b"\n")?;
    stream.flush()?;
    let mut reader = BufReader::new(stream);
    let mut response = String::new();
    let read = match reader.read_line(&mut response) {
        Ok(read) => read,
        Err(error)
            if close_wait_timeout.is_some()
                && matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
        {
            return Err(CliError::new(
                "RPC_RESPONSE_TIMEOUT",
                "Timed out waiting for the daemon to return an RPC response",
            ));
        }
        Err(error) => return Err(error.into()),
    };
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
    parse_response(response)
}

pub fn request_wait_for_close<T: DeserializeOwned>(
    socket_path: &Path,
    method: &str,
    params: Value,
    close_wait_timeout: Duration,
) -> Result<T> {
    let request = json!({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": 1
    });
    let response = raw_wait_for_close(
        socket_path,
        &serde_json::to_string(&request)?,
        close_wait_timeout,
    )?;
    parse_response(response)
}

fn parse_response<T: DeserializeOwned>(response: String) -> Result<T> {
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
        let mut cli_error = CliError::new("RPC_ERROR", format!("{code}: {message}"));
        cli_error
            .details
            .insert("backendCode".to_string(), code.to_string());
        return Err(cli_error);
    }
    let result = value.get("result").ok_or_else(|| {
        CliError::new(
            "RPC_RESPONSE_INVALID",
            "JSON-RPC response did not include a result field",
        )
    })?;
    Ok(serde_json::from_value(result.clone())?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Write};
    use std::os::unix::net::UnixListener;
    use std::thread;
    use std::time::Instant;

    #[test]
    fn request_preserves_backend_error_code_in_details() {
        let temp = tempfile::tempdir().expect("temp");
        let socket_path = temp.path().join("kast.sock");
        let listener = UnixListener::bind(&socket_path).expect("bind");
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            assert!(request_line.contains("\"method\":\"raw/resolve\""));
            let response = json!({
                "jsonrpc": "2.0",
                "id": 1,
                "error": {
                    "code": -32409,
                    "message": "multiple declarations matched",
                    "data": {
                        "code": "AMBIGUOUS_ANCHOR",
                        "message": "multiple declarations matched"
                    }
                }
            });
            writeln!(stream, "{response}").expect("write response");
        });

        let error = request::<Value>(&socket_path, "raw/resolve", json!({}))
            .expect_err("backend error should map to CliError");
        handle.join().expect("server thread");
        assert_eq!(error.code, "RPC_ERROR");
        assert_eq!(
            error.details.get("backendCode").map(String::as_str),
            Some("AMBIGUOUS_ANCHOR")
        );
        assert!(error.message.contains("AMBIGUOUS_ANCHOR"));
    }

    #[test]
    fn raw_wait_for_close_times_out_waiting_for_initial_response() {
        let temp = tempfile::tempdir().expect("temp");
        let socket_path = temp.path().join("kast.sock");
        let listener = UnixListener::bind(&socket_path).expect("bind");
        let handle = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept");
            thread::sleep(Duration::from_millis(200));
        });

        let error = raw_wait_for_close(
            &socket_path,
            r#"{"jsonrpc":"2.0","method":"raw/resolve","id":1}"#,
            Duration::from_millis(10),
        )
        .expect_err("missing response should time out");
        handle.join().expect("server thread");
        assert_eq!(error.code, "RPC_RESPONSE_TIMEOUT");
    }

    #[test]
    fn raw_wait_for_close_returns_after_response_line_without_socket_close() {
        let temp = tempfile::tempdir().expect("temp");
        let socket_path = temp.path().join("kast.sock");
        let listener = UnixListener::bind(&socket_path).expect("bind");
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            writeln!(
                stream,
                r#"{{"jsonrpc":"2.0","id":1,"result":{{"ok":true}}}}"#
            )
            .expect("write response");
            thread::sleep(Duration::from_millis(200));
        });

        let started = Instant::now();
        let response = raw_wait_for_close(
            &socket_path,
            r#"{"jsonrpc":"2.0","method":"health","id":1}"#,
            Duration::from_millis(500),
        )
        .expect("response line should return without waiting for socket close");

        assert!(
            started.elapsed() < Duration::from_millis(100),
            "client waited for socket close after receiving response line"
        );
        handle.join().expect("server thread");
        assert_eq!(response, r#"{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"#);
    }
}
