fn read_message<R: BufRead>(reader: &mut R) -> Result<Option<Value>> {
    let mut content_length = None;
    loop {
        let mut header = String::new();
        let read = reader.read_line(&mut header)?;
        if read == 0 {
            return Ok(None);
        }
        let header = header.trim_end_matches(['\r', '\n']);
        if header.is_empty() {
            break;
        }
        if let Some(value) = header.strip_prefix("Content-Length:") {
            content_length = Some(value.trim().parse::<usize>().map_err(|error| {
                CliError::new(
                    "LSP_FRAME_INVALID",
                    format!("invalid Content-Length: {error}"),
                )
            })?);
        }
    }
    let length = content_length.ok_or_else(|| {
        CliError::new(
            "LSP_FRAME_INVALID",
            "LSP message is missing Content-Length header",
        )
    })?;
    let mut buffer = vec![0_u8; length];
    reader.read_exact(&mut buffer)?;
    Ok(Some(serde_json::from_slice(&buffer)?))
}

fn write_message<W: Write>(writer: &mut W, value: &Value) -> Result<()> {
    let body = serde_json::to_vec(value)?;
    write!(writer, "Content-Length: {}\r\n\r\n", body.len())?;
    writer.write_all(&body)?;
    writer.flush()?;
    Ok(())
}

fn success_response(id: Value, result: Value) -> Value {
    json!({
        "jsonrpc": JSONRPC_VERSION,
        "id": id,
        "result": result
    })
}

fn error_response(id: Value, error: LspError) -> Value {
    json!({
        "jsonrpc": JSONRPC_VERSION,
        "id": id,
        "error": {
            "code": error.code,
            "message": error.message,
            "data": {
                "code": error.data_code
            }
        }
    })
}

type LspResult<T> = std::result::Result<T, LspError>;

#[derive(Debug)]
struct LspError {
    code: i64,
    data_code: String,
    message: String,
}

impl LspError {
    fn invalid_request(message: impl Into<String>) -> Self {
        Self {
            code: -32600,
            data_code: "LSP_INVALID_REQUEST".to_string(),
            message: message.into(),
        }
    }

    fn invalid_params(message: impl Into<String>) -> Self {
        Self {
            code: -32602,
            data_code: "LSP_INVALID_PARAMS".to_string(),
            message: message.into(),
        }
    }

    fn method_not_found(message: impl Into<String>) -> Self {
        Self {
            code: -32601,
            data_code: "LSP_METHOD_NOT_FOUND".to_string(),
            message: message.into(),
        }
    }

    fn backend_contract(message: impl Into<String>) -> Self {
        Self::server_error("LSP_BACKEND_CONTRACT_INVALID", message)
    }

    fn server_error(data_code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: -32000,
            data_code: data_code.into(),
            message: message.into(),
        }
    }
}

impl From<CliError> for LspError {
    fn from(value: CliError) -> Self {
        let data_code = value
            .details
            .get("backendCode")
            .cloned()
            .unwrap_or_else(|| value.code.to_string());
        Self::server_error(data_code, value.message)
    }
}

fn string_field<'a>(value: &'a Value, field: &str) -> LspResult<&'a str> {
    value
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| LspError::invalid_params(format!("missing string field `{field}`")))
}

fn usize_field(value: &Value, field: &str) -> LspResult<usize> {
    let raw = value
        .get(field)
        .and_then(Value::as_u64)
        .ok_or_else(|| LspError::invalid_params(format!("missing integer field `{field}`")))?;
    usize::try_from(raw)
        .map_err(|_| LspError::invalid_params(format!("field `{field}` is too large")))
}
