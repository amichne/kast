#!/usr/bin/env node
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [, , rawTargetPath, targetKind = "directory"] = process.argv;
const manifestDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(manifestDir, "../../..");
const targetPath = rawTargetPath ? resolve(rawTargetPath) : join(repoRoot, "cli-rs/resources/kast-skill");
const agentToolsFile = process.env.KAST_AGENT_TOOLS_FILE ? resolve(process.env.KAST_AGENT_TOOLS_FILE) : null;

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

function isKastCase(item) {
  return item.expectedPrimitive?.type === "skill" && item.expectedPrimitive?.name === "kast";
}

function isNegativeCase(item) {
  return item.expectedPrimitive?.type === "none" && item.expectedPrimitive?.name === "none";
}

function checkToolEnvelope(path) {
  const failures = [];
  const payload = readJson(path);
  const result = payload.result ?? {};
  const tools = Array.isArray(result.tools) ? result.tools : [];
  const toolByName = new Map(tools.map((tool) => [tool.name, tool]));
  const methodByName = new Map(tools.map((tool) => [tool.method, tool]));
  const catalogHash = result.catalogSha256 ?? "";

  failIf(payload.ok !== true, "agent tools envelope must have ok=true", failures);
  failIf(payload.method !== "agent/tools", "agent tools envelope method must be agent/tools", failures);
  failIf(result.type !== "KAST_AGENT_TOOLS", "agent tools result.type must be KAST_AGENT_TOOLS", failures);
  failIf(result.schemaVersion < 3, "agent tools schemaVersion must be at least 3", failures);
  failIf(result.toolCount !== tools.length, "agent tools toolCount must match tools.length", failures);
  failIf(!/^[a-f0-9]{64}$/.test(catalogHash), "agent tools catalogSha256 must be a 64-character lowercase hex digest", failures);

  const requiredTools = [
    "kast_symbol_query",
    "kast_callers",
    "kast_metrics",
    "kast_scaffold",
    "kast_write_and_validate",
  ];
  for (const name of requiredTools) {
    failIf(!toolByName.has(name), `agent tools envelope missing ${name}`, failures);
  }

  const requiredMethods = ["symbol/query", "symbol/callers", "database/metrics"];
  for (const method of requiredMethods) {
    failIf(!methodByName.has(method), `agent tools envelope missing method ${method}`, failures);
  }

  const symbolQuery = toolByName.get("kast_symbol_query")?.description ?? "";
  failIf(!symbolQuery.includes("unknown symbols"), "kast_symbol_query must mention unknown symbols", failures);
  failIf(!symbolQuery.includes(".kt/.kts"), "kast_symbol_query must mention .kt/.kts discovery", failures);

  const metrics = toolByName.get("kast_metrics")?.description ?? "";
  failIf(!metrics.includes("source-index metrics"), "kast_metrics must mention source-index metrics", failures);
  failIf(!metrics.includes("database-backed view"), "kast_metrics must mention database-backed view", failures);

  const leakedTools = [];
  for (const tool of tools) {
    for (const needle of leakNeedles) {
      if ((tool.description ?? "").includes(needle)) {
        leakedTools.push(`${tool.name}:${needle}`);
      }
    }
  }
  failures.push(...leakedTools);

  return check(
    "routing-agent-tools-envelope",
    failures.length === 0 ? "pass" : "fail",
    failures.length === 0 ? "info" : "error",
    failures.length === 0
      ? "Live agent tool discovery exposes the same public navigation surface as the routing corpus."
      : "Live agent tool discovery drifted from the routing contract.",
    failures.length === 0 ? [`tools=${tools.length}`, `catalogSha256=${catalogHash}`] : failures,
    ["Keep kast agent tools aligned with the routing corpus and public catalog metadata."],
  );
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
    "routing-required-cases",
    missingCaseIds.length === 0 ? "pass" : "fail",
    missingCaseIds.length === 0 ? "info" : "error",
    missingCaseIds.length === 0
      ? "Routing corpus covers initial pickup, continuous use, recovery, efficiency, negative routing, and public API boundary."
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
    } else if (action.kind === "script") {
      const scriptPath = join(targetPath, action.name);
      if (!existsSync(scriptPath)) {
        actionFailures.push(`${item.id}: missing script ${action.name}`);
      }
    } else if (action.kind === "generic" && !isNegativeCase(item)) {
      actionFailures.push(`${item.id}: generic action is only valid for negative routing cases`);
    }
  }
}
checks.push(
  check(
    "routing-actions-resolve",
    actionFailures.length === 0 ? "pass" : "fail",
    actionFailures.length === 0 ? "info" : "error",
    actionFailures.length === 0
      ? "All routing actions resolve to public Kast methods, tools, commands, packaged scripts, or negative generic actions."
      : "Some routing actions do not resolve.",
    actionFailures.length === 0 ? [...actionNames].sort() : actionFailures,
    ["Keep allowedActions aligned with references/commands.json, kast agent tools, and packaged scripts."],
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

const continuityEvidence = [
  "Keep using Kast after the first successful call",
  "A first Kast result is not a handoff back to generic file reads",
];
checks.push(
  check(
    "routing-continuous-use-guidance",
    includesAll(skill, continuityEvidence) ? "pass" : "fail",
    includesAll(skill, continuityEvidence) ? "info" : "error",
    includesAll(skill, continuityEvidence)
      ? "Skill tells agents to keep follow-up Kotlin work on Kast after initial pickup."
      : "Skill does not clearly preserve Kast use after the first successful call.",
    continuityEvidence,
    ["Add concise continuity guidance to SKILL.md."],
  ),
);

const referenceRouterEvidence = [
  "Normal use loads only SKILL.md",
  "Do not pre-load the full catalog",
  "Load `references/runbook.md` only",
];
checks.push(
  check(
    "routing-reference-router-guidance",
    includesAll(skill, referenceRouterEvidence) ? "pass" : "fail",
    includesAll(skill, referenceRouterEvidence) ? "info" : "error",
    includesAll(skill, referenceRouterEvidence)
      ? "Skill routes reference loading by need instead of encouraging eager reference reads."
      : "Skill does not provide a strict enough reference-loading router.",
    referenceRouterEvidence,
    ["Keep SKILL.md as trigger/router text and push detail into references only when needed."],
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
  "EFFICIENCY_DRIFT",
  "OVER_TRIGGER",
]);
for (const item of cases) {
  failIf(!allowedTypes.has(item.type), `${item.id}: invalid type ${item.type}`, caseShapeFailures);
  failIf(!isKastCase(item) && !isNegativeCase(item), `${item.id}: expectedPrimitive must be kast or none`, caseShapeFailures);
  failIf(isNegativeCase(item) && item.type !== "OVER_TRIGGER", `${item.id}: negative cases must use OVER_TRIGGER`, caseShapeFailures);
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
  if (!isKastCase(item)) continue;
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
  "kast agent setup skill --source-dir",
  "kast agent tools",
  "scripts/verify-kast-state.py",
];
const missingActions = requiredActions.filter((name) => !actionNames.has(name));
checks.push(
  check(
    "routing-required-public-actions",
    missingActions.length === 0 ? "pass" : "fail",
    missingActions.length === 0 ? "info" : "error",
    missingActions.length === 0
      ? "Routing eval exposes symbol calls, database metrics, high-level workflows, source-override recovery, and agent tool discovery."
      : "Routing eval is missing required public action coverage.",
    missingActions.length === 0 ? requiredActions : missingActions,
    ["Add allowedActions for missing public methods, tools, or workflow commands."],
  ),
);

const referenceFailures = [];
const referenceCases = cases.filter((item) => item.type === "EFFICIENCY_DRIFT");
failIf(referenceCases.length === 0, "routing corpus needs at least one EFFICIENCY_DRIFT reference-budget case", referenceFailures);
for (const item of referenceCases) {
  const expectation = item.referenceExpectation ?? {};
  const alwaysLoaded = new Set(expectation.alwaysLoaded ?? []);
  const forbiddenReferences = new Set(expectation.forbiddenReferences ?? []);
  failIf(!alwaysLoaded.has("SKILL.md"), `${item.id}: referenceExpectation.alwaysLoaded must include SKILL.md`, referenceFailures);
  for (const required of [
    "references/commands.json before exact field lookup",
    "references/requests/ before sample need",
    "references/runbook.md for ordinary symbol lookup",
  ]) {
    failIf(!forbiddenReferences.has(required), `${item.id}: referenceExpectation must forbid ${required}`, referenceFailures);
  }
}
checks.push(
  check(
    "routing-reference-budget-cases",
    referenceFailures.length === 0 ? "pass" : "fail",
    referenceFailures.length === 0 ? "info" : "error",
    referenceFailures.length === 0
      ? "Routing corpus includes explicit reference-loading budget cases."
      : "Routing corpus is missing reference-loading budget evidence.",
    referenceFailures.length === 0 ? referenceCases.map((item) => item.id) : referenceFailures,
    ["Add EFFICIENCY_DRIFT cases with referenceExpectation budgets."],
  ),
);

const negativeFailures = [];
const negativeCases = cases.filter((item) => item.type === "OVER_TRIGGER" || isNegativeCase(item));
failIf(negativeCases.length === 0, "routing corpus needs at least one negative over-trigger case", negativeFailures);
for (const item of negativeCases) {
  failIf(!isNegativeCase(item), `${item.id}: OVER_TRIGGER case must expect no Kast primitive`, negativeFailures);
  for (const action of item.allowedActions ?? []) {
    failIf(action.kind !== "generic", `${item.id}: negative allowedActions must stay generic`, negativeFailures);
  }
  const forbidden = new Set(item.forbiddenActions ?? []);
  failIf(!forbidden.has("kast agent workflow"), `${item.id}: negative case must forbid kast agent workflow`, negativeFailures);
  failIf(!forbidden.has("symbol/query"), `${item.id}: negative case must forbid symbol/query`, negativeFailures);
}
checks.push(
  check(
    "routing-negative-cases",
    negativeFailures.length === 0 ? "pass" : "fail",
    negativeFailures.length === 0 ? "info" : "error",
    negativeFailures.length === 0 ? "Routing corpus prevents unrelated work from over-triggering Kast." : "Routing corpus negative cases are incomplete.",
    negativeFailures.length === 0 ? negativeCases.map((item) => item.id) : negativeFailures,
    ["Keep negative cases explicit and free of Kast allowed actions."],
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

if (agentToolsFile) {
  checks.push(checkToolEnvelope(agentToolsFile));
}

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
