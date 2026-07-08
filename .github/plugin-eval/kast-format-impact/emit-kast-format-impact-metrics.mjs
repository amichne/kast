#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [, , rawTargetPath] = process.argv;
const manifestDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(manifestDir, "../../..");
const targetPath = rawTargetPath ? resolve(rawTargetPath) : join(repoRoot, "cli-rs/resources/kast-skill");
const observedPath = process.env.KAST_FORMAT_IMPACT_OBSERVED_JSONL
  ? resolve(process.env.KAST_FORMAT_IMPACT_OBSERVED_JSONL)
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
    category: "kast-format-impact",
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
    category: "kast-format-impact",
    value,
    unit,
    band,
  };
}

function failIf(condition, message, failures) {
  if (condition) failures.push(message);
}

function isPositiveCase(item) {
  return item.expectedPrimitive?.type === "skill" && item.expectedPrimitive?.name === "kast";
}

function isNegativeCase(item) {
  return item.expectedPrimitive?.type === "none" && item.expectedPrimitive?.name === "none";
}

const corpus = readJson(join(targetPath, "fixtures/maintenance/evals/format-impact.json"));
const schema = readJson(join(targetPath, "fixtures/maintenance/evals/format-impact.schema.json"));
const cases = Array.isArray(corpus.cases) ? corpus.cases : [];
const checks = [];

const schemaFailures = [];
failIf(corpus.$schema !== "./format-impact.schema.json", "format impact corpus must link ./format-impact.schema.json", schemaFailures);
failIf(corpus.schemaVersion !== 1, "format impact corpus schemaVersion must be 1", schemaFailures);
failIf(JSON.stringify(corpus.formats) !== JSON.stringify(["json", "toon"]), "format impact corpus formats must be [json, toon]", schemaFailures);
failIf(schema.properties?.cases?.items?.$ref !== "#/$defs/case", "format impact schema must define typed case items", schemaFailures);
checks.push(
  check(
    "format-impact-schema-backed",
    schemaFailures.length === 0 ? "pass" : "fail",
    schemaFailures.length === 0 ? "info" : "error",
    schemaFailures.length === 0 ? "Format impact corpus is schema-backed." : "Format impact corpus schema contract failed.",
    schemaFailures.length === 0 ? [corpus.$schema] : schemaFailures,
    ["Keep format-impact.json linked to format-impact.schema.json with schemaVersion 1."],
  ),
);

const requiredCaseIds = new Set([
  "typed-command-plan-comprehension",
  "symbol-result-extraction",
  "relationship-navigation-continuation",
  "read-only-plan-recovery",
  "typed-sequence-evidence",
  "non-kotlin-negative-routing",
  "large-typed-output-efficiency",
]);
const caseIds = new Set(cases.map((item) => item.id));
const missingCaseIds = [...requiredCaseIds].filter((id) => !caseIds.has(id));
checks.push(
  check(
    "format-impact-required-cases",
    missingCaseIds.length === 0 ? "pass" : "fail",
    missingCaseIds.length === 0 ? "info" : "error",
    missingCaseIds.length === 0
      ? "Format impact corpus covers typed command plans, extraction, relationship continuation, plan recovery, typed sequence evidence, negative routing, and large read-only output."
      : "Format impact corpus is missing required coverage cases.",
    missingCaseIds.length === 0 ? [...caseIds].sort() : missingCaseIds,
    ["Add missing cases to fixtures/maintenance/evals/format-impact.json."],
  ),
);

const caseFailures = [];
for (const item of cases) {
  failIf(JSON.stringify(item.pairedFormats) !== JSON.stringify(corpus.formats), `${item.id}: pairedFormats must match corpus formats`, caseFailures);
  failIf(item.reportOnly !== true, `${item.id}: live accuracy cases must stay report-only`, caseFailures);
  failIf(!Array.isArray(item.goldFacts) || item.goldFacts.length < 2, `${item.id}: goldFacts needs at least two entries`, caseFailures);
  failIf(!Array.isArray(item.answerScoring?.requiredTerms) || item.answerScoring.requiredTerms.length < 1, `${item.id}: answerScoring.requiredTerms needs at least one entry`, caseFailures);
  failIf(!Array.isArray(item.answerScoring?.forbiddenTerms), `${item.id}: answerScoring.forbiddenTerms must be present`, caseFailures);
  failIf(!isPositiveCase(item) && !isNegativeCase(item), `${item.id}: expectedPrimitive must be kast or none`, caseFailures);

  const forbidden = new Set(item.forbiddenActions ?? []);
  if (isPositiveCase(item)) {
    failIf(!forbidden.has("grep"), `${item.id}: positive case must forbid grep`, caseFailures);
    failIf(!forbidden.has("rg"), `${item.id}: positive case must forbid rg`, caseFailures);
  }
  if (isNegativeCase(item)) {
    failIf(!(item.expectedActions ?? []).every((action) => action.kind === "generic"), `${item.id}: negative expected actions must be generic`, caseFailures);
    failIf(!forbidden.has("kast agent symbol"), `${item.id}: negative case must forbid kast agent symbol`, caseFailures);
    failIf(!forbidden.has("kast agent impact"), `${item.id}: negative case must forbid kast agent impact`, caseFailures);
  }
}
checks.push(
  check(
    "format-impact-case-evidence",
    caseFailures.length === 0 ? "pass" : "fail",
    caseFailures.length === 0 ? "info" : "error",
    caseFailures.length === 0
      ? "Every format impact case is paired, report-only, and has gold facts, forbidden-action expectations, plus deterministic answer scoring terms."
      : "One or more format impact cases are incomplete.",
    caseFailures.length === 0 ? cases.map((item) => `${item.id}:${item.scenario}`) : caseFailures,
    ["Keep JSON/TOON case pairs explicit and report-only until live accuracy thresholds stabilize."],
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
    "format-impact-observed-pairs",
    observedFailures.length === 0 ? "pass" : "fail",
    observedFailures.length === 0 ? "info" : "error",
    observedPath
      ? observedFailures.length === 0
        ? "Observed JSONL includes complete JSON/TOON pairs with decoded-equivalent records."
        : "Observed JSONL has incomplete pairs or semantic mismatches."
      : "No observed JSONL supplied; live accuracy remains report-only and unmeasured.",
    observedFailures.length === 0 ? [`records=${observedRecords.length}`] : observedFailures,
    ["Run .github/scripts/run-kast-format-impact-report.sh to generate observed JSONL."],
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
let answerMessage = "No captured answers were supplied; answer accuracy remains unmeasured.";
let answerEvidence = missingAnswerRecords.length > 0 ? missingAnswerRecords : ["records=0"];
if (evaluated.length > 0 && answerFailures.length === 0 && missingAnswerRecords.length === 0) {
  answerStatus = "pass";
  answerSeverity = "info";
  answerMessage = "Captured answers satisfy every required term and avoid forbidden actions.";
  answerEvidence = [`answers=${evaluated.length}`, `passRate=${answerPassRate}`];
} else if (evaluated.length > 0 && answerFailures.length === 0) {
  answerMessage = "Captured answer scoring is partial; some JSON/TOON pairs were not supplied.";
  answerEvidence = missingAnswerRecords;
} else if (answerFailures.length > 0) {
  answerMessage = "Captured answers missed required terms or used forbidden actions.";
  answerEvidence = answerFailures;
}
checks.push(
  check(
    "format-impact-answer-scoring",
    answerStatus,
    answerSeverity,
    answerMessage,
    answerEvidence,
    ["Generate answers from cli-rs/target/format-impact/answer-requests.jsonl and set KAST_FORMAT_IMPACT_ANSWERS_JSONL before rerunning the report."],
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
        metric("kast-format-impact-score", score, "percent", score === 100 ? "excellent" : score >= 85 ? "good" : "needs-work"),
        metric("kast-format-impact-cases", cases.length, "cases", cases.length >= requiredCaseIds.size ? "good" : "needs-work"),
        metric("kast-format-impact-observed-records", observedRecords.length, "records", observedRecords.length >= cases.length * 2 ? "good" : "report-only"),
        metric("kast-format-impact-byte-reduction", byteReduction, "percent", byteReduction > 0 ? "good" : "unmeasured"),
        metric("kast-format-impact-live-answers", evaluated.length, "records", evaluated.length > 0 ? "measured" : "report-only"),
        metric("kast-format-impact-answer-pass-rate", answerPassRate, "percent", evaluated.length > 0 ? answerPassRate === 100 ? "excellent" : answerPassRate >= 85 ? "good" : "needs-work" : "unmeasured"),
        metric("kast-format-impact-answer-failures", answerFailures.length, "records", answerFailures.length === 0 ? "good" : "needs-work"),
      ],
      artifacts: [
        {
          id: "kast-format-impact-corpus",
          type: "json",
          label: "Kast TOON format impact corpus",
          description: "Paired JSON versus TOON cases for agent output comprehension and cost checks.",
        },
        {
          id: "kast-format-impact-observed-jsonl",
          type: "jsonl",
          label: "Kast TOON format impact observed records",
          description: "Paired JSON/TOON fixture records with optional captured-answer verdicts.",
        },
        {
          id: "kast-format-impact-answer-requests",
          type: "jsonl",
          label: "Kast TOON format impact answer requests",
          description: "Prompt and input rows to feed an external agent/model runner before scoring captured answers.",
        },
      ],
    },
    null,
    2,
  ),
);
