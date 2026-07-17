#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [, , rawTargetPath] = process.argv;
const manifestDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(manifestDir, "../../..");
const targetPath = rawTargetPath ? resolve(rawTargetPath) : join(repoRoot, "cli-rs/resources/kast-skill");
const maintenancePath = join(repoRoot, "cli-rs/protocol/maintenance/evals");
const observedPath = process.env.KAST_ROUTING_FORMAT_IMPACT_OBSERVED_JSONL
  ? resolve(process.env.KAST_ROUTING_FORMAT_IMPACT_OBSERVED_JSONL)
  : null;

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function readJsonl(path) {
  return readFileSync(path, "utf8")
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function check(id, status, severity, message, evidence = [], remediation = []) {
  return {
    id,
    category: "kast-routing-format-impact",
    severity,
    status,
    message,
    evidence,
    remediation,
  };
}

function metric(id, value, unit, band) {
  return {
    id,
    category: "kast-routing-format-impact",
    value,
    unit,
    band,
  };
}

function failIf(condition, message, failures) {
  if (condition) failures.push(message);
}

function collectStrings(value, strings = []) {
  if (typeof value === "string") {
    strings.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) collectStrings(item, strings);
  } else if (value && typeof value === "object") {
    for (const item of Object.values(value)) collectStrings(item, strings);
  }
  return strings;
}

const corpus = readJson(join(maintenancePath, "routing.json"));
const schema = readJson(join(maintenancePath, "routing.schema.json"));
const cases = Array.isArray(corpus.cases) ? corpus.cases : [];
const checks = [];

const schemaFailures = [];
failIf(corpus.$schema !== "./routing.schema.json", "routing corpus must link ./routing.schema.json", schemaFailures);
failIf(corpus.schemaVersion !== 1, "routing corpus schemaVersion must be 1", schemaFailures);
failIf(corpus.primitive?.name !== "kast", "routing corpus primitive.name must be kast", schemaFailures);
failIf(schema.properties?.cases?.items?.$ref !== "#/$defs/case", "routing schema must define typed case items", schemaFailures);
checks.push(
  check(
    "routing-format-impact-schema-backed",
    schemaFailures.length === 0 ? "pass" : "fail",
    schemaFailures.length === 0 ? "info" : "error",
    schemaFailures.length === 0 ? "Routing JSON/TOON comparison suite is backed by the routing corpus schema." : "Routing JSON/TOON comparison suite schema contract failed.",
    schemaFailures.length === 0 ? [corpus.$schema] : schemaFailures,
    ["Keep routing.json linked to routing.schema.json with schemaVersion 1."],
  ),
);

const requiredCaseIds = new Set([
  "kotlin-file-trigger-all-kt-kts",
  "unknown-symbol-navigation",
  "relationship-navigation",
  "source-index-database-access",
  "agent-workflow-public-surface",
  "continuous-kast-use-after-first-call",
  "source-override-skill-recovery",
  "reference-budget-symbol-query",
  "non-kotlin-docs-negative-case",
  "public-api-boundary",
]);
const caseIds = new Set(cases.map((item) => item.id));
const missingCaseIds = [...requiredCaseIds].filter((id) => !caseIds.has(id));
checks.push(
  check(
    "routing-format-impact-required-cases",
    missingCaseIds.length === 0 ? "pass" : "fail",
    missingCaseIds.length === 0 ? "info" : "error",
    missingCaseIds.length === 0
      ? "Routing JSON/TOON comparison suite covers every routing eval case."
      : "Routing JSON/TOON comparison suite is missing required cases.",
    missingCaseIds.length === 0 ? [...caseIds].sort() : missingCaseIds,
    ["Keep the routing comparison suite derived from every case in cli-rs/protocol/maintenance/evals/routing.json."],
  ),
);

const caseFailures = [];
for (const item of cases) {
  failIf(!item.prompt, `${item.id}: prompt is required`, caseFailures);
  failIf(!item.expectedPrimitive?.name, `${item.id}: expectedPrimitive.name is required`, caseFailures);
  failIf(!Array.isArray(item.allowedActions) || item.allowedActions.length === 0, `${item.id}: allowedActions are required`, caseFailures);
  failIf(!Array.isArray(item.forbiddenActions) || item.forbiddenActions.length === 0, `${item.id}: forbiddenActions are required`, caseFailures);
  failIf(!item.recoveryExpectation, `${item.id}: recoveryExpectation is required`, caseFailures);
  failIf(!Array.isArray(item.verificationEvidence) || item.verificationEvidence.length < 2, `${item.id}: verificationEvidence needs at least two entries`, caseFailures);
}
checks.push(
  check(
    "routing-format-impact-case-evidence",
    caseFailures.length === 0 ? "pass" : "fail",
    caseFailures.length === 0 ? "info" : "error",
    caseFailures.length === 0
      ? "Every routing case has enough evidence to generate JSON/TOON answer requests."
      : "One or more routing cases cannot generate scoreable JSON/TOON answer requests.",
    caseFailures.length === 0 ? cases.map((item) => item.id) : caseFailures,
    ["Keep routing cases prompt-bearing, action-bearing, and recovery-bearing so both encodings are scoreable."],
  ),
);

const localPathNeedles = ["/Users/", "/home/", "/private/", "C:\\"];
const localPathHits = collectStrings(corpus).filter((text) =>
  localPathNeedles.some((needle) => text.includes(needle)),
);
checks.push(
  check(
    "routing-format-impact-corpus-sanitized",
    localPathHits.length === 0 ? "pass" : "fail",
    localPathHits.length === 0 ? "info" : "error",
    localPathHits.length === 0 ? "Routing JSON/TOON comparison corpus contains no local absolute path markers." : "Routing JSON/TOON comparison corpus contains local absolute path markers.",
    localPathHits.length === 0 ? [`cases=${cases.length}`] : localPathHits,
    ["Sanitize durable routing cases before committing."],
  ),
);

let observedRecords = [];
if (observedPath) {
  observedRecords = readJsonl(observedPath);
}

const observedFailures = [];
const recordsByCase = new Map();
for (const record of observedRecords) {
  const key = record.caseId;
  if (!recordsByCase.has(key)) recordsByCase.set(key, []);
  recordsByCase.get(key).push(record);
  failIf(record.decodedEquivalent !== true, `${record.caseId}/${record.format}: decodedEquivalent must be true`, observedFailures);
}
for (const item of cases) {
  const records = recordsByCase.get(item.id) ?? [];
  if (observedPath) {
    const formats = new Set(records.map((record) => record.format));
    failIf(!formats.has("json"), `${item.id}: missing json observed record`, observedFailures);
    failIf(!formats.has("toon"), `${item.id}: missing toon observed record`, observedFailures);
  }
}
checks.push(
  check(
    "routing-format-impact-observed-pairs",
    observedFailures.length === 0 ? "pass" : "fail",
    observedFailures.length === 0 ? "info" : "error",
    observedPath
      ? observedFailures.length === 0
        ? "Observed JSONL includes complete routing JSON/TOON pairs with decoded-equivalent records."
        : "Observed JSONL has incomplete routing pairs or semantic mismatches."
      : "No observed JSONL supplied; routing JSON/TOON accuracy remains report-only and unmeasured.",
    observedFailures.length === 0 ? [`records=${observedRecords.length}`] : observedFailures,
    ["Run .github/scripts/run-kast-routing-format-impact-report.sh to generate observed JSONL."],
  ),
);

const evaluated = observedRecords.filter((record) => record.answerVerdict && record.answerVerdict !== "not_evaluated");
const answerFailures = [];
const missingAnswerRecords = [];
for (const record of observedRecords) {
  if (!record.answerVerdict || record.answerVerdict === "not_evaluated") {
    missingAnswerRecords.push(`${record.caseId}/${record.format}`);
    continue;
  }
  if (record.answerVerdict !== "pass") {
    const missing = (record.missingRequiredTerms ?? []).join(", ");
    const forbidden = (record.forbiddenHits ?? []).join(", ");
    answerFailures.push(`${record.caseId}/${record.format}: missing=[${missing}] forbidden=[${forbidden}]`);
  }
}
const answerPassCount = evaluated.filter((record) => record.answerVerdict === "pass").length;
const answerPassRate = evaluated.length > 0 ? Math.round((answerPassCount / evaluated.length) * 10000) / 100 : 0;
let answerStatus = "warn";
let answerSeverity = "warning";
let answerMessage = "No captured routing answers were supplied; answer accuracy remains unmeasured.";
let answerEvidence = missingAnswerRecords.length > 0 ? missingAnswerRecords : ["records=0"];
if (evaluated.length > 0 && answerFailures.length === 0 && missingAnswerRecords.length === 0) {
  answerStatus = "pass";
  answerSeverity = "info";
  answerMessage = "Captured routing answers satisfy every required term and avoid forbidden actions.";
  answerEvidence = [`answers=${evaluated.length}`, `passRate=${answerPassRate}`];
} else if (evaluated.length > 0 && answerFailures.length === 0) {
  answerMessage = "Captured routing answer scoring is partial; some JSON/TOON pairs were not supplied.";
  answerEvidence = missingAnswerRecords;
} else if (answerFailures.length > 0) {
  answerMessage = "Captured routing answers missed required terms or used forbidden actions.";
  answerEvidence = answerFailures;
}
checks.push(
  check(
    "routing-format-impact-answer-scoring",
    answerStatus,
    answerSeverity,
    answerMessage,
    answerEvidence,
    ["Generate answers from cli-rs/target/routing-format-impact/answer-requests.jsonl and set KAST_ROUTING_FORMAT_IMPACT_ANSWERS_JSONL before rerunning the report."],
  ),
);

const jsonBytes = observedRecords
  .filter((record) => record.format === "json")
  .reduce((sum, record) => sum + (record.stdoutBytes ?? 0), 0);
const toonBytes = observedRecords
  .filter((record) => record.format === "toon")
  .reduce((sum, record) => sum + (record.stdoutBytes ?? 0), 0);
const byteReduction = jsonBytes > 0 ? Math.round(((jsonBytes - toonBytes) / jsonBytes) * 10000) / 100 : 0;
const passCount = checks.filter((item) => item.status === "pass").length;
const score = Math.round((passCount / checks.length) * 100);

console.log(
  JSON.stringify(
    {
      checks,
      metrics: [
        metric("kast-routing-format-impact-score", score, "percent", score === 100 ? "excellent" : score >= 85 ? "good" : "needs-work"),
        metric("kast-routing-format-impact-cases", cases.length, "cases", cases.length >= requiredCaseIds.size ? "good" : "needs-work"),
        metric("kast-routing-format-impact-observed-records", observedRecords.length, "records", observedRecords.length >= cases.length * 2 ? "good" : "report-only"),
        metric("kast-routing-format-impact-byte-reduction", byteReduction, "percent", byteReduction > 0 ? "good" : "unmeasured"),
        metric("kast-routing-format-impact-live-answers", evaluated.length, "records", evaluated.length > 0 ? "measured" : "report-only"),
        metric("kast-routing-format-impact-answer-pass-rate", answerPassRate, "percent", evaluated.length > 0 ? answerPassRate === 100 ? "excellent" : answerPassRate >= 85 ? "good" : "needs-work" : "unmeasured"),
        metric("kast-routing-format-impact-answer-failures", answerFailures.length, "records", answerFailures.length === 0 ? "good" : "needs-work"),
      ],
      artifacts: [
        {
          id: "kast-routing-format-impact-corpus",
          type: "json",
          label: "Kast routing JSON/TOON comparison corpus",
          description: "Routing eval cases rendered as paired JSON and TOON model inputs for AXI comparison.",
        },
        {
          id: "kast-routing-format-impact-observed-jsonl",
          type: "jsonl",
          label: "Kast routing JSON/TOON observed records",
          description: "Paired routing JSON/TOON records with optional captured-answer verdicts.",
        },
        {
          id: "kast-routing-format-impact-answer-requests",
          type: "jsonl",
          label: "Kast routing JSON/TOON answer requests",
          description: "Prompt and input rows to feed an external agent/model runner before scoring captured routing answers.",
        },
      ],
    },
    null,
    2,
  ),
);
