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
  "tool-catalog-comprehension",
  "symbol-result-extraction",
  "relationship-navigation-continuation",
  "validation-error-recovery",
  "workflow-evidence-json-artifacts",
  "non-kotlin-negative-routing",
  "large-read-only-catalog-efficiency",
]);
const caseIds = new Set(cases.map((item) => item.id));
const missingCaseIds = [...requiredCaseIds].filter((id) => !caseIds.has(id));
checks.push(
  check(
    "format-impact-required-cases",
    missingCaseIds.length === 0 ? "pass" : "fail",
    missingCaseIds.length === 0 ? "info" : "error",
    missingCaseIds.length === 0
      ? "Format impact corpus covers catalog comprehension, extraction, relationship continuation, validation recovery, workflow evidence, negative routing, and large read-only output."
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
  failIf(!isPositiveCase(item) && !isNegativeCase(item), `${item.id}: expectedPrimitive must be kast or none`, caseFailures);

  const forbidden = new Set(item.forbiddenActions ?? []);
  if (isPositiveCase(item)) {
    failIf(!forbidden.has("grep"), `${item.id}: positive case must forbid grep`, caseFailures);
    failIf(!forbidden.has("rg"), `${item.id}: positive case must forbid rg`, caseFailures);
  }
  if (isNegativeCase(item)) {
    failIf(!(item.expectedActions ?? []).every((action) => action.kind === "generic"), `${item.id}: negative expected actions must be generic`, caseFailures);
    failIf(!forbidden.has("kast agent call"), `${item.id}: negative case must forbid kast agent call`, caseFailures);
  }
}
checks.push(
  check(
    "format-impact-case-evidence",
    caseFailures.length === 0 ? "pass" : "fail",
    caseFailures.length === 0 ? "info" : "error",
    caseFailures.length === 0
      ? "Every format impact case is paired, report-only, and has gold facts plus forbidden-action expectations."
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
  failIf((record.forbiddenHits ?? []).length > 0, `${record.caseId}/${record.format}: forbidden hits ${record.forbiddenHits.join(", ")}`, observedFailures);
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

const jsonBytes = observedRecords
  .filter((record) => record.format === "json")
  .reduce((sum, record) => sum + (record.stdoutBytes ?? 0), 0);
const toonBytes = observedRecords
  .filter((record) => record.format === "toon")
  .reduce((sum, record) => sum + (record.stdoutBytes ?? 0), 0);
const byteReduction = jsonBytes > 0 ? Math.round(((jsonBytes - toonBytes) / jsonBytes) * 10000) / 100 : 0;
const evaluated = observedRecords.filter((record) => record.answerVerdict && record.answerVerdict !== "not_evaluated");
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
          description: "Report-only paired JSON/TOON fixture records and optional live-agent verdicts.",
        },
      ],
    },
    null,
    2,
  ),
);
