use std::collections::{BTreeMap, BTreeSet};
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
    answer_requests: Option<PathBuf>,
    answers: Option<PathBuf>,
    suite: Suite,
    agent_output_shape: AgentOutputShape,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Suite {
    FormatImpact,
    Routing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AgentOutputShape {
    Text,
    Json,
    Toon,
}

impl AgentOutputShape {
    fn as_str(self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::Json => "json",
            Self::Toon => "toon",
        }
    }

    fn answer_instructions(self) -> &'static str {
        match self {
            Self::Text => {
                "Return concise plain text that names selected actions, summarizes facts and recovery, and states forbidden actions were avoided without listing them."
            }
            Self::Json => {
                "Return one JSON object with selectedActions, facts, recovery, and forbiddenActionsAvoided fields; do not copy forbidden action names."
            }
            Self::Toon => {
                "Return one TOON object with selectedActions, facts, recovery, and forbiddenActionsAvoided fields; do not copy forbidden action names."
            }
        }
    }
}

impl Suite {
    fn as_str(self) -> &'static str {
        match self {
            Self::FormatImpact => "format-impact",
            Self::Routing => "routing",
        }
    }
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
    expected_actions: Vec<Action>,
    forbidden_actions: Vec<String>,
    gold_facts: Vec<String>,
    answer_scoring: AnswerScoring,
    #[serde(default, skip_deserializing)]
    model_input: Option<Value>,
}

#[derive(Debug, Deserialize)]
struct InputArtifact {
    kind: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
struct Action {
    kind: String,
    name: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AnswerScoring {
    required_terms: Vec<String>,
    forbidden_terms: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AnswerRecord {
    case_id: String,
    format: String,
    answer: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AnswerRequest {
    case_id: String,
    format: String,
    prompt: String,
    input: String,
    input_bytes: usize,
    agent_output_shape: String,
    answer_instructions: String,
    expected_actions: Vec<Action>,
    forbidden_actions: Vec<String>,
    gold_facts: Vec<String>,
    answer_scoring: AnswerScoring,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImpactRecord {
    case_id: String,
    format: String,
    prompt: String,
    observed_actions: Vec<String>,
    forbidden_hits: Vec<String>,
    matched_required_terms: Vec<String>,
    missing_required_terms: Vec<String>,
    extracted_facts: Vec<String>,
    answer_verdict: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    answer_score: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    answer: Option<String>,
    stdout_bytes: usize,
    decoded_equivalent: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImpactSummary {
    ok: bool,
    suite: String,
    cases: usize,
    records: usize,
    decoded_equivalent: bool,
    json_stdout_bytes: usize,
    toon_stdout_bytes: usize,
    byte_reduction_percent: Option<f64>,
    answer_verdict: String,
    evaluated_answers: usize,
    passing_answers: usize,
    answer_accuracy_percent: Option<f64>,
    agent_output_shape: String,
    output: PathBuf,
    answer_requests: Option<PathBuf>,
    answers: Option<PathBuf>,
}

#[derive(Debug)]
struct FormatOutput {
    format: &'static str,
    stdout_bytes: usize,
    text: String,
    decoded: Value,
}

#[derive(Debug)]
struct AnswerScore {
    observed_actions: Vec<String>,
    forbidden_hits: Vec<String>,
    matched_required_terms: Vec<String>,
    missing_required_terms: Vec<String>,
    score: f64,
    verdict: &'static str,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoutingCase {
    id: String,
    #[serde(rename = "type")]
    case_type: String,
    prompt: String,
    expected_primitive: Primitive,
    allowed_actions: Vec<Action>,
    forbidden_actions: Vec<String>,
    recovery_expectation: String,
    verification_evidence: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct Primitive {
    name: String,
}

fn main() -> Result<(), Box<dyn Error>> {
    let args = parse_args()?;
    let corpus = read_corpus(&args.target, args.suite)?;
    let answers = match &args.answers {
        Some(path) => read_answer_records(path)?,
        None => BTreeMap::new(),
    };
    validate_answer_records(&corpus, &answers)?;
    let run_root = make_run_root()?;
    let mut records = Vec::new();
    let mut answer_requests = Vec::new();

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
            let answer = answers
                .get(&(case.id.clone(), output.format.to_string()))
                .cloned();
            let answer_score = answer.as_deref().map(|answer| score_answer(case, answer));
            answer_requests.push(answer_request(case, &output, args.agent_output_shape));
            records.push(ImpactRecord {
                case_id: case.id.clone(),
                format: output.format.to_string(),
                prompt: case.prompt.clone(),
                observed_actions: answer_score
                    .as_ref()
                    .map(|score| score.observed_actions.clone())
                    .unwrap_or_default(),
                forbidden_hits: answer_score
                    .as_ref()
                    .map(|score| score.forbidden_hits.clone())
                    .unwrap_or_default(),
                matched_required_terms: answer_score
                    .as_ref()
                    .map(|score| score.matched_required_terms.clone())
                    .unwrap_or_default(),
                missing_required_terms: answer_score
                    .as_ref()
                    .map(|score| score.missing_required_terms.clone())
                    .unwrap_or_default(),
                extracted_facts: extracted_facts(case, &output.decoded),
                answer_verdict: answer_score
                    .as_ref()
                    .map(|score| score.verdict.to_string())
                    .unwrap_or_else(|| "not_evaluated".to_string()),
                answer_score: answer_score.as_ref().map(|score| score.score),
                answer,
                stdout_bytes: output.stdout_bytes,
                decoded_equivalent,
            });
        }
    }

    write_jsonl(&args.output, &records)?;
    if let Some(path) = &args.answer_requests {
        write_jsonl(path, &answer_requests)?;
    }
    let summary = summarize(
        args.suite,
        &records,
        args.output.clone(),
        args.answer_requests.clone(),
        args.answers.clone(),
        args.agent_output_shape,
    );
    println!("{}", serde_json::to_string_pretty(&summary)?);
    let _ = fs::remove_dir_all(run_root);
    Ok(())
}

fn parse_args() -> Result<Args, Box<dyn Error>> {
    let mut kast_bin = None;
    let mut target = None;
    let mut output = None;
    let mut answer_requests = None;
    let mut answers = None;
    let mut suite = Suite::FormatImpact;
    let mut agent_output_shape = AgentOutputShape::Text;
    let mut raw = std::env::args().skip(1);

    while let Some(arg) = raw.next() {
        match arg.as_str() {
            "--kast-bin" => kast_bin = raw.next().map(PathBuf::from),
            "--target" => target = raw.next().map(PathBuf::from),
            "--output" => output = raw.next().map(PathBuf::from),
            "--answer-requests" => answer_requests = raw.next().map(PathBuf::from),
            "--answers" => answers = raw.next().map(PathBuf::from),
            "--suite" => {
                suite = match raw.next().as_deref() {
                    Some("format-impact") => Suite::FormatImpact,
                    Some("routing") => Suite::Routing,
                    Some(other) => {
                        return Err(format!(
                            "unsupported --suite `{other}`; expected format-impact or routing"
                        )
                        .into());
                    }
                    None => return Err("missing value for --suite".into()),
                };
            }
            "--agent-output-shape" => {
                agent_output_shape = match raw.next().as_deref() {
                    Some("text") => AgentOutputShape::Text,
                    Some("json") => AgentOutputShape::Json,
                    Some("toon") => AgentOutputShape::Toon,
                    Some(other) => {
                        return Err(format!(
                            "unsupported --agent-output-shape `{other}`; expected text, json, or toon"
                        )
                        .into());
                    }
                    None => return Err("missing value for --agent-output-shape".into()),
                };
            }
            other => return Err(format!("unexpected argument `{other}`").into()),
        }
    }

    Ok(Args {
        kast_bin: kast_bin.ok_or("missing --kast-bin")?,
        target: target.unwrap_or_else(|| PathBuf::from("cli-rs/resources/kast-skill")),
        output: output.ok_or("missing --output")?,
        answer_requests,
        answers,
        suite,
        agent_output_shape,
    })
}

fn read_corpus(target: &Path, suite: Suite) -> Result<Corpus, Box<dyn Error>> {
    match suite {
        Suite::FormatImpact => read_format_impact_corpus(target),
        Suite::Routing => read_routing_corpus(target),
    }
}

fn evals_dir(target: &Path) -> PathBuf {
    target.join("fixtures").join("maintenance").join("evals")
}

fn read_format_impact_corpus(target: &Path) -> Result<Corpus, Box<dyn Error>> {
    let path = target
        .join("fixtures")
        .join("maintenance")
        .join("evals")
        .join("format-impact.json");
    let content = fs::read_to_string(&path)?;
    Ok(serde_json::from_str(&content)?)
}

fn read_routing_corpus(target: &Path) -> Result<Corpus, Box<dyn Error>> {
    let path = evals_dir(target).join("routing.json");
    let content = fs::read_to_string(&path)?;
    let root: Value = serde_json::from_str(&content)?;
    let raw_cases = root
        .get("cases")
        .and_then(Value::as_array)
        .ok_or("routing corpus must contain cases array")?;
    let mut cases = Vec::with_capacity(raw_cases.len());
    for raw_case in raw_cases {
        let routing_case: RoutingCase = serde_json::from_value(raw_case.clone())?;
        cases.push(routing_case.into_impact_case(raw_case.clone()));
    }
    Ok(Corpus { cases })
}

fn read_answer_records(path: &Path) -> Result<BTreeMap<(String, String), String>, Box<dyn Error>> {
    let content = fs::read_to_string(path)?;
    let mut answers = BTreeMap::new();
    for (index, line) in content.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let record: AnswerRecord = serde_json::from_str(line)
            .map_err(|error| format!("{}:{}: {error}", path.display(), index + 1))?;
        let key = (record.case_id, record.format);
        if answers.insert(key.clone(), record.answer).is_some() {
            return Err(format!(
                "{}:{}: duplicate answer for {}/{}",
                path.display(),
                index + 1,
                key.0,
                key.1
            )
            .into());
        }
    }
    Ok(answers)
}

fn validate_answer_records(
    corpus: &Corpus,
    answers: &BTreeMap<(String, String), String>,
) -> Result<(), Box<dyn Error>> {
    let expected_keys = corpus
        .cases
        .iter()
        .flat_map(|case| ["json", "toon"].map(move |format| (case.id.clone(), format.to_string())))
        .collect::<BTreeSet<_>>();
    for key in answers.keys() {
        if !expected_keys.contains(key) {
            return Err(format!(
                "answer supplied for unknown case/format {}/{}",
                key.0, key.1
            )
            .into());
        }
    }
    Ok(())
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
            &["--output", "json", "agent", "tools", "--full"],
            &["agent", "tools", "--full"],
        ),
        "validation-error" => command_outputs(
            kast_bin,
            run_root,
            &["--output", "json", "agent", "call", "symbol/resolve"],
            &["agent", "call", "symbol/resolve"],
        ),
        "workflow-dry-run" => {
            let out_dir = run_root.join("workflow").join(&case.id);
            let out_dir = out_dir.to_str().ok_or("workflow path must be utf-8")?;
            command_outputs(
                kast_bin,
                run_root,
                &[
                    "--output",
                    "json",
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
        "routing-case" => model_input_outputs(case),
        "prompt-only" => Ok(vec![
            FormatOutput {
                format: "json",
                stdout_bytes: 0,
                text: String::new(),
                decoded: Value::Null,
            },
            FormatOutput {
                format: "toon",
                stdout_bytes: 0,
                text: String::new(),
                decoded: Value::Null,
            },
        ]),
        other => Err(format!("unsupported input artifact kind `{other}`").into()),
    }
}

fn model_input_outputs(case: &ImpactCase) -> Result<Vec<FormatOutput>, Box<dyn Error>> {
    let value = case
        .model_input
        .clone()
        .ok_or_else(|| format!("case {} missing model input", case.id))?;
    encode_value_outputs(value)
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
    let json_stdout_bytes = json_stdout.len();
    let json_text = String::from_utf8(json_stdout)?;
    let toon_stdout_bytes = toon_stdout.len();
    let toon_text = String::from_utf8(toon_stdout)?;
    let toon_decoded = toon_format::decode_default(toon_text.trim())?;

    Ok(vec![
        FormatOutput {
            format: "json",
            stdout_bytes: json_stdout_bytes,
            text: json_text,
            decoded: json_decoded,
        },
        FormatOutput {
            format: "toon",
            stdout_bytes: toon_stdout_bytes,
            text: toon_text,
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
    encode_value_outputs(value)
}

fn encode_value_outputs(value: Value) -> Result<Vec<FormatOutput>, Box<dyn Error>> {
    let mut json_text = serde_json::to_string_pretty(&value)?;
    json_text.push('\n');
    let toon_text = toon_format::encode_default(&value)?;
    let toon_decoded = toon_format::decode_default(toon_text.trim())?;
    let json_decoded = serde_json::from_str(&json_text)?;

    Ok(vec![
        FormatOutput {
            format: "json",
            stdout_bytes: json_text.len(),
            text: json_text,
            decoded: json_decoded,
        },
        FormatOutput {
            format: "toon",
            stdout_bytes: toon_text.len(),
            text: toon_text,
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

fn answer_request(
    case: &ImpactCase,
    output: &FormatOutput,
    agent_output_shape: AgentOutputShape,
) -> AnswerRequest {
    AnswerRequest {
        case_id: case.id.clone(),
        format: output.format.to_string(),
        prompt: format!(
            "{}\n\n{}",
            case.prompt,
            agent_output_shape.answer_instructions()
        ),
        input: output.text.clone(),
        input_bytes: output.stdout_bytes,
        agent_output_shape: agent_output_shape.as_str().to_string(),
        answer_instructions: agent_output_shape.answer_instructions().to_string(),
        expected_actions: case.expected_actions.clone(),
        forbidden_actions: case.forbidden_actions.clone(),
        gold_facts: case.gold_facts.clone(),
        answer_scoring: case.answer_scoring.clone(),
    }
}

fn score_answer(case: &ImpactCase, answer: &str) -> AnswerScore {
    let matched_required_terms = case
        .answer_scoring
        .required_terms
        .iter()
        .filter(|term| contains_term(answer, term))
        .cloned()
        .collect::<Vec<_>>();
    let missing_required_terms = case
        .answer_scoring
        .required_terms
        .iter()
        .filter(|term| !contains_term(answer, term))
        .cloned()
        .collect::<Vec<_>>();
    let forbidden_hits = case
        .answer_scoring
        .forbidden_terms
        .iter()
        .filter(|term| contains_term(answer, term))
        .cloned()
        .collect::<Vec<_>>();
    let observed_actions = case
        .expected_actions
        .iter()
        .filter(|action| contains_term(answer, &action.name))
        .map(|action| action.name.clone())
        .collect::<Vec<_>>();
    let required_score = if case.answer_scoring.required_terms.is_empty() {
        100.0
    } else {
        (matched_required_terms.len() as f64 / case.answer_scoring.required_terms.len() as f64)
            * 100.0
    };
    let score = if forbidden_hits.is_empty() {
        required_score
    } else {
        0.0
    };
    let verdict = if missing_required_terms.is_empty() && forbidden_hits.is_empty() {
        "pass"
    } else {
        "fail"
    };

    AnswerScore {
        observed_actions,
        forbidden_hits,
        matched_required_terms,
        missing_required_terms,
        score,
        verdict,
    }
}

fn contains_term(answer: &str, term: &str) -> bool {
    let answer = answer.to_ascii_lowercase();
    let term = term.to_ascii_lowercase();
    if is_token_term(&term) {
        return answer
            .split(|character: char| !character.is_ascii_alphanumeric())
            .any(|token| token == term);
    }
    answer.contains(&term)
}

fn is_token_term(term: &str) -> bool {
    term.chars()
        .all(|character| character.is_ascii_alphanumeric())
        && term.len() <= 4
}

fn write_jsonl<T: Serialize>(path: &Path, records: &[T]) -> Result<(), Box<dyn Error>> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut file = File::create(path)?;
    for record in records {
        writeln!(file, "{}", serde_json::to_string(record)?)?;
    }
    Ok(())
}

fn summarize(
    suite: Suite,
    records: &[ImpactRecord],
    output: PathBuf,
    answer_requests: Option<PathBuf>,
    answers: Option<PathBuf>,
    agent_output_shape: AgentOutputShape,
) -> ImpactSummary {
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
    let evaluated_answers = records
        .iter()
        .filter(|record| record.answer_verdict != "not_evaluated")
        .count();
    let passing_answers = records
        .iter()
        .filter(|record| record.answer_verdict == "pass")
        .count();
    let answer_accuracy_percent = if evaluated_answers > 0 {
        Some((passing_answers as f64 / evaluated_answers as f64) * 100.0)
    } else {
        None
    };
    let answer_verdict = if evaluated_answers == 0 {
        "not_evaluated"
    } else if passing_answers == evaluated_answers {
        "pass"
    } else if passing_answers == 0 {
        "fail"
    } else {
        "partial"
    };

    ImpactSummary {
        ok: true,
        suite: suite.as_str().to_string(),
        cases: records.len() / 2,
        records: records.len(),
        decoded_equivalent: records.iter().all(|record| record.decoded_equivalent),
        json_stdout_bytes,
        toon_stdout_bytes,
        byte_reduction_percent,
        answer_verdict: answer_verdict.to_string(),
        evaluated_answers,
        passing_answers,
        answer_accuracy_percent,
        agent_output_shape: agent_output_shape.as_str().to_string(),
        output,
        answer_requests,
        answers,
    }
}

impl RoutingCase {
    fn into_impact_case(self, raw_case: Value) -> ImpactCase {
        let mut required_terms = Vec::new();
        required_terms.push(self.expected_primitive.name.clone());
        for action in &self.allowed_actions {
            if !required_terms.contains(&action.name) {
                required_terms.push(action.name.clone());
            }
        }
        required_terms.push("recovery".to_string());

        let mut gold_facts = vec![
            format!("The routing case type is {}.", self.case_type),
            format!(
                "The expected primitive is {}.",
                self.expected_primitive.name
            ),
            format!("Recovery expectation: {}", self.recovery_expectation),
        ];
        gold_facts.extend(self.verification_evidence.iter().cloned());

        ImpactCase {
            id: self.id,
            prompt: format!(
                "Given this Kast routing eval case, identify the expected primitive, list every allowed public action, avoid forbidden fallbacks, and preserve the recovery expectation. Original prompt: {}",
                self.prompt
            ),
            input_artifact: InputArtifact {
                kind: "routing-case".to_string(),
            },
            expected_actions: self.allowed_actions,
            forbidden_actions: self.forbidden_actions.clone(),
            gold_facts,
            answer_scoring: AnswerScoring {
                required_terms,
                forbidden_terms: self.forbidden_actions,
            },
            model_input: Some(json!({
                "suite": "routing",
                "case": raw_case,
            })),
        }
    }
}
