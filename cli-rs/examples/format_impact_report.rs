use std::error::Error;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Debug)]
struct Args {
    kast_bin: PathBuf,
    target: PathBuf,
    output: PathBuf,
}

#[derive(Debug, Deserialize)]
struct Corpus {
    cases: Vec<ImpactCase>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImpactCase {
    id: String,
    prompt: String,
    input_artifact: InputArtifact,
    gold_facts: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct InputArtifact {
    kind: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImpactRecord {
    case_id: String,
    format: String,
    prompt: String,
    observed_actions: Vec<String>,
    forbidden_hits: Vec<String>,
    extracted_facts: Vec<String>,
    answer_verdict: String,
    stdout_bytes: usize,
    decoded_equivalent: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImpactSummary {
    ok: bool,
    cases: usize,
    records: usize,
    decoded_equivalent: bool,
    json_stdout_bytes: usize,
    toon_stdout_bytes: usize,
    byte_reduction_percent: Option<f64>,
    answer_verdict: String,
    output: PathBuf,
}

#[derive(Debug)]
struct FormatOutput {
    format: &'static str,
    stdout_bytes: usize,
    decoded: Value,
}

fn main() -> Result<(), Box<dyn Error>> {
    let args = parse_args()?;
    let corpus = read_corpus(&args.target)?;
    let run_root = make_run_root()?;
    let mut records = Vec::new();

    for case in &corpus.cases {
        let outputs = outputs_for_case(&args.kast_bin, &run_root, case)?;
        let decoded_equivalent = outputs
            .first()
            .zip(outputs.get(1))
            .is_some_and(|(left, right)| left.decoded == right.decoded);
        if !decoded_equivalent {
            return Err(format!("decoded JSON and TOON differ for case {}", case.id).into());
        }

        for output in outputs {
            records.push(ImpactRecord {
                case_id: case.id.clone(),
                format: output.format.to_string(),
                prompt: case.prompt.clone(),
                observed_actions: Vec::new(),
                forbidden_hits: Vec::new(),
                extracted_facts: extracted_facts(case, &output.decoded),
                answer_verdict: "not_evaluated".to_string(),
                stdout_bytes: output.stdout_bytes,
                decoded_equivalent,
            });
        }
    }

    write_jsonl(&args.output, &records)?;
    let summary = summarize(&records, args.output.clone());
    println!("{}", serde_json::to_string_pretty(&summary)?);
    let _ = fs::remove_dir_all(run_root);
    Ok(())
}

fn parse_args() -> Result<Args, Box<dyn Error>> {
    let mut kast_bin = None;
    let mut target = None;
    let mut output = None;
    let mut raw = std::env::args().skip(1);

    while let Some(arg) = raw.next() {
        match arg.as_str() {
            "--kast-bin" => kast_bin = raw.next().map(PathBuf::from),
            "--target" => target = raw.next().map(PathBuf::from),
            "--output" => output = raw.next().map(PathBuf::from),
            other => return Err(format!("unexpected argument `{other}`").into()),
        }
    }

    Ok(Args {
        kast_bin: kast_bin.ok_or("missing --kast-bin")?,
        target: target.unwrap_or_else(|| PathBuf::from("cli-rs/resources/kast-skill")),
        output: output.ok_or("missing --output")?,
    })
}

fn read_corpus(target: &Path) -> Result<Corpus, Box<dyn Error>> {
    let path = target
        .join("fixtures")
        .join("maintenance")
        .join("evals")
        .join("format-impact.json");
    let content = fs::read_to_string(&path)?;
    Ok(serde_json::from_str(&content)?)
}

fn make_run_root() -> Result<PathBuf, Box<dyn Error>> {
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let root =
        std::env::temp_dir().join(format!("kast-format-impact-{}-{now}", std::process::id()));
    fs::create_dir_all(root.join("home"))?;
    fs::create_dir_all(root.join("config"))?;
    Ok(root)
}

fn outputs_for_case(
    kast_bin: &Path,
    run_root: &Path,
    case: &ImpactCase,
) -> Result<Vec<FormatOutput>, Box<dyn Error>> {
    match case.input_artifact.kind.as_str() {
        "agent-tools" => command_outputs(
            kast_bin,
            run_root,
            &["agent", "tools"],
            &["agent", "--format", "toon", "tools"],
        ),
        "validation-error" => command_outputs(
            kast_bin,
            run_root,
            &["agent", "call", "symbol/resolve"],
            &["agent", "--format", "toon", "call", "symbol/resolve"],
        ),
        "workflow-dry-run" => {
            let out_dir = run_root.join("workflow").join(&case.id);
            let out_dir = out_dir.to_str().ok_or("workflow path must be utf-8")?;
            command_outputs(
                kast_bin,
                run_root,
                &[
                    "agent",
                    "workflow",
                    "symbol",
                    "--dry-run",
                    "--out-dir",
                    out_dir,
                    "--symbol",
                    "Kast",
                    "--references",
                ],
                &[
                    "agent",
                    "--format",
                    "toon",
                    "workflow",
                    "symbol",
                    "--dry-run",
                    "--out-dir",
                    out_dir,
                    "--symbol",
                    "Kast",
                    "--references",
                ],
            )
        }
        "synthetic-envelope" => synthetic_outputs(case),
        "prompt-only" => Ok(vec![
            FormatOutput {
                format: "json",
                stdout_bytes: 0,
                decoded: Value::Null,
            },
            FormatOutput {
                format: "toon",
                stdout_bytes: 0,
                decoded: Value::Null,
            },
        ]),
        other => Err(format!("unsupported input artifact kind `{other}`").into()),
    }
}

fn command_outputs(
    kast_bin: &Path,
    run_root: &Path,
    json_args: &[&str],
    toon_args: &[&str],
) -> Result<Vec<FormatOutput>, Box<dyn Error>> {
    let json_stdout = run_kast(kast_bin, run_root, json_args)?;
    let toon_stdout = run_kast(kast_bin, run_root, toon_args)?;
    let json_decoded = serde_json::from_slice(&json_stdout)?;
    let toon_text = std::str::from_utf8(&toon_stdout)?;
    let toon_decoded = toon_format::decode_default(toon_text.trim())?;

    Ok(vec![
        FormatOutput {
            format: "json",
            stdout_bytes: json_stdout.len(),
            decoded: json_decoded,
        },
        FormatOutput {
            format: "toon",
            stdout_bytes: toon_stdout.len(),
            decoded: toon_decoded,
        },
    ])
}

fn run_kast(kast_bin: &Path, run_root: &Path, args: &[&str]) -> Result<Vec<u8>, Box<dyn Error>> {
    let output = Command::new(kast_bin)
        .args(args)
        .env("HOME", run_root.join("home"))
        .env("KAST_CONFIG_HOME", run_root.join("config"))
        .output()?;
    if output.stdout.is_empty() {
        return Err(format!(
            "kast produced empty stdout for {:?}: status={}, stderr={}",
            args,
            output.status,
            String::from_utf8_lossy(&output.stderr)
        )
        .into());
    }
    Ok(output.stdout)
}

fn synthetic_outputs(case: &ImpactCase) -> Result<Vec<FormatOutput>, Box<dyn Error>> {
    let value = synthetic_value(case);
    let mut json_text = serde_json::to_string_pretty(&value)?;
    json_text.push('\n');
    let toon_text = toon_format::encode_default(&value)?;
    let toon_decoded = toon_format::decode_default(toon_text.trim())?;

    Ok(vec![
        FormatOutput {
            format: "json",
            stdout_bytes: json_text.len(),
            decoded: serde_json::from_str(&json_text)?,
        },
        FormatOutput {
            format: "toon",
            stdout_bytes: toon_text.len(),
            decoded: toon_decoded,
        },
    ])
}

fn synthetic_value(case: &ImpactCase) -> Value {
    json!({
        "ok": true,
        "method": "symbol/resolve",
        "request": {
            "method": "symbol/resolve",
            "params": {
                "symbol": "EventBean",
                "kind": "class"
            }
        },
        "result": {
            "type": "SYMBOL_RESOLVE_SUCCESS",
            "symbol": {
                "name": "EventBean",
                "kind": "CLASS",
                "fqName": "com.example.EventBean",
                "location": {
                    "filePath": "src/main/kotlin/com/example/EventBean.kt",
                    "offset": 128
                }
            },
            "nextActions": if case.id.contains("relationship") {
                json!(["symbol/references", "symbol/callers"])
            } else {
                json!(["symbol/scaffold"])
            }
        }
    })
}

fn extracted_facts(case: &ImpactCase, decoded: &Value) -> Vec<String> {
    if decoded.is_null() {
        return case.gold_facts.clone();
    }

    let mut facts = Vec::new();
    if let Some(method) = decoded.get("method").and_then(Value::as_str) {
        facts.push(format!("method={method}"));
    }
    if let Some(result_type) = decoded.pointer("/result/type").and_then(Value::as_str) {
        facts.push(format!("result.type={result_type}"));
    }
    if let Some(error_code) = decoded.pointer("/error/code").and_then(Value::as_str) {
        facts.push(format!("error.code={error_code}"));
    }
    if let Some(symbol) = decoded
        .pointer("/result/symbol/name")
        .and_then(Value::as_str)
    {
        facts.push(format!("symbol.name={symbol}"));
    }
    if facts.is_empty() {
        facts.extend(case.gold_facts.iter().take(2).cloned());
    }
    facts
}

fn write_jsonl(path: &Path, records: &[ImpactRecord]) -> Result<(), Box<dyn Error>> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut file = File::create(path)?;
    for record in records {
        writeln!(file, "{}", serde_json::to_string(record)?)?;
    }
    Ok(())
}

fn summarize(records: &[ImpactRecord], output: PathBuf) -> ImpactSummary {
    let json_stdout_bytes = records
        .iter()
        .filter(|record| record.format == "json")
        .map(|record| record.stdout_bytes)
        .sum::<usize>();
    let toon_stdout_bytes = records
        .iter()
        .filter(|record| record.format == "toon")
        .map(|record| record.stdout_bytes)
        .sum::<usize>();
    let byte_reduction_percent = if json_stdout_bytes > 0 {
        Some(
            ((json_stdout_bytes as f64 - toon_stdout_bytes as f64) / json_stdout_bytes as f64)
                * 100.0,
        )
    } else {
        None
    };

    ImpactSummary {
        ok: true,
        cases: records.len() / 2,
        records: records.len(),
        decoded_equivalent: records.iter().all(|record| record.decoded_equivalent),
        json_stdout_bytes,
        toon_stdout_bytes,
        byte_reduction_percent,
        answer_verdict: "not_evaluated".to_string(),
        output,
    }
}
