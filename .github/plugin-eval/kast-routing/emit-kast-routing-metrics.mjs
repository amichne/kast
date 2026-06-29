#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [, , rawTargetPath, targetKind = "directory"] = process.argv;
const manifestDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(manifestDir, "../../..");
const targetPath = rawTargetPath ? resolve(rawTargetPath) : join(repoRoot, "cli-rs/resources/kast-skill");

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function readText(path) {
  return readFileSync(path, "utf8");
}

function check(id, status, severity, message, evidence = [], remediation = []) {
  return {
    id,
    category: "kast-routing",
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
    category: "kast-routing",
    value,
    unit,
    band,
  };
}

function includesAll(text, needles) {
  return needles.every((needle) => text.includes(needle));
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

function failIf(condition, message, failures) {
  if (condition) failures.push(message);
}

const skill = readText(join(targetPath, "SKILL.md"));
const catalog = readJson(join(targetPath, "references/commands.json"));
const routing = readJson(join(targetPath, "fixtures/maintenance/evals/routing.json"));
const routingSchema = readJson(join(targetPath, "fixtures/maintenance/evals/routing.schema.json"));
const commands = catalog.commands ?? {};
const toolNames = new Set(
  Object.values(commands)
    .map((command) => command.tool?.name)
    .filter(Boolean),
);
const cases = Array.isArray(routing.cases) ? routing.cases : [];
const checks = [];

const schemaFailures = [];
failIf(routing.$schema !== "./routing.schema.json", "routing corpus must link ./routing.schema.json", schemaFailures);
failIf(routing.schemaVersion !== 1, "routing corpus schemaVersion must be 1", schemaFailures);
failIf(routing.primitive?.name !== "kast", "routing corpus primitive.name must be kast", schemaFailures);
failIf(routingSchema.properties?.cases?.items?.$ref !== "#/$defs/case", "routing schema must define typed case items", schemaFailures);
checks.push(
  check(
    "routing-schema-backed",
    schemaFailures.length === 0 ? "pass" : "fail",
    schemaFailures.length === 0 ? "info" : "error",
    schemaFailures.length === 0 ? "Routing corpus is schema-backed." : "Routing corpus schema contract failed.",
    schemaFailures.length === 0 ? [routing.$schema] : schemaFailures,
    ["Keep routing.json linked to routing.schema.json with schemaVersion 1."],
  ),
);

const requiredCaseIds = new Set([
  "kotlin-file-trigger-all-kt-kts",
  "unknown-symbol-navigation",
  "relationship-navigation",
  "source-index-database-access",
  "agent-workflow-public-surface",
  "public-api-boundary",
]);
const caseIds = new Set(cases.map((item) => item.id));
const missingCaseIds = [...requiredCaseIds].filter((id) => !caseIds.has(id));
checks.push(
  check(
    "routing-required-cases",
    missingCaseIds.length === 0 ? "pass" : "fail",
    missingCaseIds.length === 0 ? "info" : "error",
    missingCaseIds.length === 0
      ? "Routing corpus covers Kotlin files, symbols, relationships, database, workflows, and public API boundary."
      : "Routing corpus is missing required coverage cases.",
    missingCaseIds.length === 0 ? [...caseIds].sort() : missingCaseIds,
    ["Add missing cases to fixtures/maintenance/evals/routing.json."],
  ),
);

const actionFailures = [];
const actionNames = new Set();
for (const item of cases) {
  for (const action of item.allowedActions ?? []) {
    actionNames.add(action.name);
    if (action.kind === "method" && !commands[action.name]) {
      actionFailures.push(`${item.id}: missing method ${action.name}`);
    } else if (action.kind === "tool" && !toolNames.has(action.name)) {
      actionFailures.push(`${item.id}: missing tool ${action.name}`);
    } else if (
      action.kind === "command" &&
      !action.name.startsWith("kast agent") &&
      !action.name.startsWith("kast inspect metrics")
    ) {
      actionFailures.push(`${item.id}: non-public command ${action.name}`);
    }
  }
}
checks.push(
  check(
    "routing-actions-resolve",
    actionFailures.length === 0 ? "pass" : "fail",
    actionFailures.length === 0 ? "info" : "error",
    actionFailures.length === 0 ? "All routing actions resolve to public Kast methods, tools, or commands." : "Some routing actions do not resolve.",
    actionFailures.length === 0 ? [...actionNames].sort() : actionFailures,
    ["Keep allowedActions aligned with references/commands.json and kast agent tools."],
  ),
);

const triggerEvidence = [
  "all Kotlin `.kt` and `.kts`",
  "every `.kt` and `.kts` file",
  "only navigation surface",
  "Any `.kt` or `.kts` file",
];
checks.push(
  check(
    "routing-kotlin-file-trigger",
    includesAll(skill, triggerEvidence) ? "pass" : "fail",
    includesAll(skill, triggerEvidence) ? "info" : "error",
    includesAll(skill, triggerEvidence)
      ? "Skill explicitly triggers for all Kotlin source and script files."
      : "Skill trigger language does not fully cover Kotlin source and script files.",
    triggerEvidence,
    ["Keep SKILL.md explicit about every .kt and .kts file and the sole-navigation rule."],
  ),
);

const caseShapeFailures = [];
const allowedTypes = new Set([
  "TRIGGER_MISS",
  "WRONG_PRIMITIVE",
  "LOADED_BYPASSED",
  "ADAPTER_DRIFT",
  "SCHEMA_FRICTION",
  "SETUP_FRICTION",
]);
for (const item of cases) {
  failIf(!allowedTypes.has(item.type), `${item.id}: invalid type ${item.type}`, caseShapeFailures);
  failIf(item.expectedPrimitive?.name !== "kast", `${item.id}: expectedPrimitive must be kast`, caseShapeFailures);
  failIf(!item.observedRoute?.risk, `${item.id}: observedRoute.risk is required`, caseShapeFailures);
  failIf(!item.recoveryExpectation, `${item.id}: recoveryExpectation is required`, caseShapeFailures);
  failIf((item.verificationEvidence ?? []).length < 2, `${item.id}: verificationEvidence needs at least two entries`, caseShapeFailures);
}
checks.push(
  check(
    "routing-case-evidence",
    caseShapeFailures.length === 0 ? "pass" : "fail",
    caseShapeFailures.length === 0 ? "info" : "error",
    caseShapeFailures.length === 0
      ? "Every routing case has a miss class, observed-route risk, recovery expectation, and verification evidence."
      : "One or more routing cases are missing evidence fields.",
    caseShapeFailures.length === 0 ? cases.map((item) => `${item.id}:${item.type}`) : caseShapeFailures,
    ["Preserve typed routing miss evidence in every case."],
  ),
);

const fallbackFailures = [];
for (const item of cases) {
  const forbidden = new Set(item.forbiddenActions ?? []);
  failIf(!forbidden.has("grep"), `${item.id}: must forbid grep`, fallbackFailures);
  failIf(!forbidden.has("rg"), `${item.id}: must forbid rg`, fallbackFailures);
}
checks.push(
  check(
    "routing-forbidden-fallbacks",
    fallbackFailures.length === 0 ? "pass" : "fail",
    fallbackFailures.length === 0 ? "info" : "error",
    fallbackFailures.length === 0 ? "Every routing case forbids generic text-search fallbacks." : "Some routing cases do not reject text-search fallbacks.",
    fallbackFailures.length === 0 ? cases.map((item) => item.id) : fallbackFailures,
    ["For Kotlin semantics, every routing case must reject grep and rg."],
  ),
);

const requiredActions = [
  "symbol/query",
  "symbol/callers",
  "database/metrics",
  "kast_symbol_query",
  "kast_callers",
  "kast_metrics",
  "kast agent workflow diagnostics",
  "kast agent tools",
];
const missingActions = requiredActions.filter((name) => !actionNames.has(name));
checks.push(
  check(
    "routing-required-public-actions",
    missingActions.length === 0 ? "pass" : "fail",
    missingActions.length === 0 ? "info" : "error",
    missingActions.length === 0
      ? "Routing eval exposes symbol calls, database metrics, high-level workflows, and agent tool discovery."
      : "Routing eval is missing required public action coverage.",
    missingActions.length === 0 ? requiredActions : missingActions,
    ["Add allowedActions for missing public methods, tools, or workflow commands."],
  ),
);

const leakNeedles = [
  "capabilities.experimental.kastMethods",
  "/rpc/",
  "daemon passthrough",
  "JVM backends",
  "Rust-owned",
];
const publicSurfaceTexts = [
  skill,
  commands["symbol/query"]?.tool?.description ?? "",
  commands["symbol/callers"]?.tool?.description ?? "",
  commands["database/metrics"]?.tool?.description ?? "",
];
const leaks = [];
for (const needle of leakNeedles) {
  if (publicSurfaceTexts.some((text) => text.includes(needle))) {
    leaks.push(needle);
  }
}
checks.push(
  check(
    "routing-public-api-boundary",
    leaks.length === 0 ? "pass" : "fail",
    leaks.length === 0 ? "info" : "error",
    leaks.length === 0 ? "Skill and public tool descriptions avoid internal endpoint and implementation leaks." : "Public routing surface leaks internal details.",
    leaks.length === 0 ? ["SKILL.md", "symbol/query", "symbol/callers", "database/metrics"] : leaks,
    ["Keep generated protocol paths, LSP internals, and implementation routing out of public skill/tool descriptions."],
  ),
);

const localPathNeedles = ["/Users/", "/home/", "/private/", "C:\\"];
const localPathHits = collectStrings(routing).filter((text) =>
  localPathNeedles.some((needle) => text.includes(needle)),
);
checks.push(
  check(
    "routing-corpus-sanitized",
    localPathHits.length === 0 ? "pass" : "fail",
    localPathHits.length === 0 ? "info" : "error",
    localPathHits.length === 0 ? "Routing corpus contains no local absolute path markers." : "Routing corpus contains local absolute path markers.",
    localPathHits.length === 0 ? [`cases=${cases.length}`] : localPathHits,
    ["Sanitize durable routing cases before committing."],
  ),
);

const passCount = checks.filter((item) => item.status === "pass").length;
const score = Math.round((passCount / checks.length) * 100);

console.log(
  JSON.stringify(
    {
      checks,
      metrics: [
        metric("kast-routing-score", score, "percent", score === 100 ? "excellent" : score >= 85 ? "good" : "needs-work"),
        metric("kast-routing-cases", cases.length, "cases", cases.length >= requiredCaseIds.size ? "good" : "needs-work"),
        metric("kast-routing-public-actions", actionNames.size, "actions", actionNames.size >= requiredActions.length ? "good" : "needs-work"),
        metric("kast-routing-agent-tools", toolNames.size, "tools", toolNames.size >= 14 ? "good" : "needs-work"),
      ],
      artifacts: [
        {
          id: "kast-routing-corpus",
          type: "json",
          label: "Kast routing eval corpus",
          description: "Schema-backed corpus for Kotlin navigation and public API boundary routing checks.",
        },
      ],
    },
    null,
    2,
  ),
);
