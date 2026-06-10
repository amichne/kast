import { makeKastTools as makePackagedKastTools } from "../../cli-rs/resources/copilot-extension/extensions/kast/_shared/kast-tools.mjs";
import { makeKotlinGradleLoopTools as makePackagedGradleTools } from "../../cli-rs/resources/copilot-extension/extensions/kast/kotlin-gradle-loop/tools.mjs";
import { makeKastTools as makeRepoLocalKastTools } from "../extensions/kast/_shared/kast-tools.mjs";
import { makeKotlinGradleLoopTools as makeRepoLocalGradleTools } from "../extensions/kast/kotlin-gradle-loop/tools.mjs";
import { chmod, mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const forbiddenSchemaKeys = new Set(["oneOf", "anyOf", "allOf", "discriminator"]);

function fail(message) {
  throw new Error(message);
}

function visitSchema(value, path, errors) {
  if (!value || typeof value !== "object") return;
  if (Array.isArray(value.type)) {
    errors.push(`${path}.type must be a single provider-compatible type`);
  }
  if (Array.isArray(value.enum) && value.enum.includes(null)) {
    errors.push(`${path}.enum must not include null`);
  }
  for (const key of forbiddenSchemaKeys) {
    if (Object.prototype.hasOwnProperty.call(value, key)) {
      errors.push(`${path}.${key} is outside the Copilot provider-compatible subset`);
    }
  }
  for (const [key, child] of Object.entries(value)) {
    visitSchema(child, `${path}.${key}`, errors);
  }
}

function assertProviderCompatible(label, tools) {
  const errors = [];
  for (const tool of tools) {
    visitSchema(tool.parameters, `${label}.${tool.name}.parameters`, errors);
  }
  if (errors.length > 0) {
    fail(errors.join("\n"));
  }
}

function assertVariantTool(tools, name, expectedTypes) {
  const tool = tools.find((candidate) => candidate.name === name);
  if (!tool) fail(`${name} is missing`);
  const typeField = tool.parameters?.properties?.type;
  if (!typeField) fail(`${name} must expose a flat type discriminator`);
  const actualTypes = [...(typeField.enum ?? [])].sort();
  const sortedExpected = [...expectedTypes].sort();
  if (JSON.stringify(actualTypes) !== JSON.stringify(sortedExpected)) {
    fail(`${name} type enum mismatch: ${actualTypes.join(", ")}`);
  }
  const required = tool.parameters?.required ?? [];
  if (JSON.stringify(required) !== JSON.stringify(["type"])) {
    fail(`${name} should require only the flat type discriminator`);
  }
}

const packagedTools = makePackagedKastTools(async () => "{}");
const repoLocalTools = makeRepoLocalKastTools(async () => "{}");
const packagedGradleTools = makePackagedGradleTools();
const repoLocalGradleTools = makeRepoLocalGradleTools();
const packagedExtensionTools = [...packagedTools, ...packagedGradleTools];
const repoLocalExtensionTools = [...repoLocalTools, ...repoLocalGradleTools];

assertProviderCompatible("packaged", packagedExtensionTools);
assertProviderCompatible("repo-local", repoLocalExtensionTools);

const packagedSchema = JSON.stringify(packagedExtensionTools.map(({ handler: _handler, ...tool }) => tool));
const repoLocalSchema = JSON.stringify(repoLocalExtensionTools.map(({ handler: _handler, ...tool }) => tool));
if (packagedSchema !== repoLocalSchema) {
  fail("repo-local Copilot extension tool schema drifted from packaged schema");
}

assertVariantTool(packagedTools, "kast_rename", [
  "RENAME_BY_OFFSET_REQUEST",
  "RENAME_BY_SYMBOL_REQUEST",
]);
assertVariantTool(packagedTools, "kast_write_and_validate", [
  "CREATE_FILE_REQUEST",
  "INSERT_AT_OFFSET_REQUEST",
  "REPLACE_RANGE_REQUEST",
]);

function toolByName(tools, name) {
  const tool = tools.find((candidate) => candidate.name === name);
  if (!tool) fail(`${name} is missing`);
  return tool;
}

async function invokeJson(tool, args) {
  const raw = await tool.handler(args);
  try {
    return JSON.parse(raw);
  } catch (error) {
    fail(`${tool.name} returned non-JSON: ${raw}\n${error}`);
  }
}

async function assertGradleLoopRuntime() {
  const projectRoot = await mkdtemp(join(tmpdir(), "kast-gradle-loop-"));
  await writeFile(join(projectRoot, "settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
  const gradlew = join(projectRoot, "gradlew");
  await writeFile(
    gradlew,
    [
      "#!/usr/bin/env bash",
      "set -euo pipefail",
      "printf '> Task %s\\n' \"$1\"",
      "printf 'BUILD SUCCESSFUL in 1s\\n'",
      "",
    ].join("\n"),
  );
  await chmod(gradlew, 0o755);

  const hookBeforeConfig = await invokeJson(toolByName(packagedGradleTools, "gradle_run_hook"), { projectRoot });
  if (hookBeforeConfig.ok || hookBeforeConfig.needs_configuration !== true) {
    fail(`gradle_run_hook should request configuration before a hook is set: ${JSON.stringify(hookBeforeConfig)}`);
  }

  const init = await invokeJson(toolByName(packagedGradleTools, "gradle_init_state"), { projectRoot });
  if (!init.ok || init.already_existed !== true) {
    fail(`gradle_init_state should find the state created by gradle_run_hook: ${JSON.stringify(init)}`);
  }

  const setHook = await invokeJson(toolByName(packagedGradleTools, "gradle_set_hook"), {
    projectRoot,
    task: "test",
  });
  if (!setHook.ok) fail(`gradle_set_hook failed: ${JSON.stringify(setHook)}`);

  const hookRun = await invokeJson(toolByName(packagedGradleTools, "gradle_run_hook"), { projectRoot });
  if (!hookRun.ok || hookRun.task !== "test" || hookRun.tasks_executed !== 1) {
    fail(`gradle_run_hook did not run the configured hook: ${JSON.stringify(hookRun)}`);
  }

  const testResultsDir = join(projectRoot, "build", "test-results", "test");
  await mkdir(testResultsDir, { recursive: true });
  await writeFile(
    join(testResultsDir, "TEST-fixture.SampleTest.xml"),
    [
      '<testsuite name="fixture.SampleTest" tests="1" failures="1" errors="0" skipped="0" time="0.1">',
      '<testcase classname="fixture.SampleTest" name="fails">',
      '<failure message="broken" type="AssertionError"></failure>',
      "</testcase>",
      "</testsuite>",
      "",
    ].join("\n"),
  );

  const junit = await invokeJson(toolByName(packagedGradleTools, "gradle_parse_junit"), { projectRoot });
  if (!junit.ok || junit.failed !== 1 || junit.failures.length !== 1) {
    fail(`gradle_parse_junit did not report the fixture failure: ${JSON.stringify(junit)}`);
  }
}

await assertGradleLoopRuntime();

console.log(`Validated ${packagedExtensionTools.length} Copilot extension tool schemas and Gradle loop runtime behavior.`);
