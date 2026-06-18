"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const node_crypto_1 = require("node:crypto");
const node_fs_1 = require("node:fs");
const promises_1 = require("node:fs/promises");
const node_os_1 = require("node:os");
const node_path_1 = require("node:path");
const node_url_1 = require("node:url");
const node_child_process_1 = require("node:child_process");
const node_stream_1 = require("node:stream");
const promises_2 = require("node:stream/promises");
const node_util_1 = require("node:util");
const execFileAsync = (0, node_util_1.promisify)(node_child_process_1.execFile);
const REQUIRED_MANIFEST_FIELDS = [
    "schemaVersion",
    "kastVersion",
    "kastGitSha",
    "os",
    "arch",
    "javaVersion",
    "intellijBuild",
    "kotlinPluginVersion",
    "kastIndexSchemaVersion",
    "artifactSha256",
];
async function main() {
    assertLinuxX64();
    await requireExternalTool("tar", "extract .tar.zst runtime and Gradle cache artifacts");
    await requireExternalTool("zstd", "decompress .tar.zst runtime and Gradle cache artifacts");
    const context = readContext();
    const scratch = await makeScratch();
    try {
        await installRuntime(context, scratch);
        await installGradleCache(context, scratch);
    }
    finally {
        await (0, promises_1.rm)(scratch, { recursive: true, force: true });
    }
}
function readContext() {
    const home = process.env.HOME && process.env.HOME.trim() ? process.env.HOME : process.cwd();
    assertSingleLineValue("HOME", home);
    const cacheHome = process.env.KAST_CACHE_HOME || (0, node_path_1.join)(home, ".cache", "kast");
    const configHome = process.env.KAST_CONFIG_HOME || (0, node_path_1.join)(home, ".config", "kast");
    assertSingleLineValue("KAST_CACHE_HOME", cacheHome);
    assertSingleLineValue("KAST_CONFIG_HOME", configHome);
    return {
        version: versionInput(),
        artifactUrl: requiredInput("artifact-url"),
        artifactSha256: requiredInput("artifact-sha256"),
        manifestUrl: optionalInput("manifest-url"),
        authorizationHeader: optionalInput("authorization-header"),
        artifactAuthorizationHeader: optionalInput("artifact-authorization-header"),
        manifestAuthorizationHeader: optionalInput("manifest-authorization-header"),
        installDir: optionalInput("install-dir") || "/opt/kast",
        gradleCacheUrl: optionalInput("gradle-ro-cache-url"),
        gradleCacheSha256: optionalInput("gradle-ro-cache-sha256"),
        gradleCacheAuthorizationHeader: optionalInput("gradle-ro-cache-authorization-header"),
        failOnCacheMiss: booleanInput("fail-on-cache-miss", false),
        strict: booleanInput("strict", true),
        downloadAttempts: numberInput("download-attempts", 3, 1, 10),
        downloadRetryDelayMs: numberInput("download-retry-delay-ms", 1000, 0, 60_000),
        downloadTimeoutMs: numberInput("download-timeout-ms", 120_000, 1000, 600_000),
        home,
        cacheHome,
        configHome,
    };
}
function versionInput() {
    const value = requiredInput("version");
    const identifier = "[0-9A-Za-z-]+";
    const dottedIdentifiers = `${identifier}(?:\\.${identifier})*`;
    const semverPattern = new RegExp(`^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-${dottedIdentifiers})?(?:\\+${dottedIdentifiers})?$`);
    if (!semverPattern.test(value)) {
        throw new Error(`input version must be a semver path segment like 1.2.3 or v1.2.3, got: ${value}`);
    }
    return value;
}
function inputEnvNames(name) {
    const upper = name.toUpperCase();
    return [
        `INPUT_${upper}`,
        `INPUT_${upper.replace(/-/g, "_")}`,
    ];
}
function optionalInput(name) {
    for (const envName of inputEnvNames(name)) {
        const value = process.env[envName];
        if (value !== undefined && value.trim() !== "") {
            assertSingleLineValue(`input ${name}`, value);
            return value.trim();
        }
    }
    return "";
}
function requiredInput(name) {
    const value = optionalInput(name);
    if (!value) {
        throw new Error(`missing required input: ${name}`);
    }
    return value;
}
function booleanInput(name, defaultValue) {
    const raw = optionalInput(name);
    if (!raw)
        return defaultValue;
    if (/^(1|true|yes)$/i.test(raw))
        return true;
    if (/^(0|false|no)$/i.test(raw))
        return false;
    throw new Error(`input ${name} must be true or false, got: ${raw}`);
}
function numberInput(name, defaultValue, min, max) {
    const raw = optionalInput(name);
    if (!raw)
        return defaultValue;
    if (!/^[0-9]+$/.test(raw)) {
        throw new Error(`input ${name} must be an integer, got: ${raw}`);
    }
    const value = Number(raw);
    if (value < min || value > max) {
        throw new Error(`input ${name} must be between ${min} and ${max}, got: ${value}`);
    }
    return value;
}
function assertSingleLineValue(label, value) {
    if (/[\r\n]/.test(value)) {
        throw new Error(`${label} must not contain line breaks`);
    }
}
function assertLinuxX64() {
    const runnerOs = (process.env.RUNNER_OS || process.platform).toLowerCase();
    const runnerArch = (process.env.RUNNER_ARCH || process.arch).toLowerCase();
    const linux = runnerOs === "linux";
    const x64 = runnerArch === "x64" || runnerArch === "x86_64";
    if (!linux || !x64) {
        throw new Error(`unsupported platform: ${runnerOs}/${runnerArch}; setup-kast currently supports linux/x64`);
    }
}
async function requireExternalTool(command, purpose) {
    try {
        await execFileAsync(command, ["--version"], { timeout: 10_000 });
    }
    catch (error) {
        throw new Error(`missing required tool '${command}' needed to ${purpose}; install ${command} before running setup-kast: ${messageOf(error)}`);
    }
}
async function installRuntime(context, scratch) {
    const archive = (0, node_path_1.join)(scratch, "kast-headless-linux-x64.tar.zst");
    await download(context.artifactUrl, archive, context, {
        label: "runtime artifact",
        authorizationHeader: context.artifactAuthorizationHeader || context.authorizationHeader,
    });
    const artifactDigest = await verifyChecksum(archive, context.artifactSha256, "runtime artifact");
    const extractDir = (0, node_path_1.join)(scratch, "runtime-extract");
    await (0, promises_1.mkdir)(extractDir, { recursive: true });
    await assertSafeTarArchive(archive);
    await execFileAsync("tar", ["--zstd", "-xf", archive, "-C", extractDir]);
    await assertNoSymbolicLinks(extractDir);
    const runtimeRoot = await findRuntimeRoot(extractDir);
    const targetDir = (0, node_path_1.join)(context.installDir, context.version);
    const preparedRoot = (0, node_path_1.join)(scratch, "runtime-prepared");
    await (0, promises_1.rm)(preparedRoot, { recursive: true, force: true });
    await (0, promises_1.cp)(runtimeRoot, preparedRoot, { recursive: true, force: true });
    await (0, promises_1.chmod)((0, node_path_1.join)(preparedRoot, "bin", "kast"), 0o755);
    const manifest = validateManifest(await resolveManifest(context, scratch, preparedRoot), context.version, artifactDigest);
    await (0, promises_1.writeFile)((0, node_path_1.join)(preparedRoot, "kast-runtime-manifest.json"), JSON.stringify(manifest, null, 2) + "\n", "utf8");
    const stagingDir = (0, node_path_1.join)(context.installDir, `.setup-kast-${context.version}-${process.pid}-${Date.now()}`);
    const current = (0, node_path_1.join)(context.installDir, "current");
    const previousCurrent = await readCurrentSymlink(current);
    const configPath = kastConfigPath(context);
    const previousConfig = await readOptionalTextFile(configPath);
    let attemptedCurrentPublish = false;
    let cleanupStaging = false;
    try {
        cleanupStaging = true;
        await installTree(preparedRoot, stagingDir);
        await writeKastConfig(context, stagingDir);
        await runKastChecks(context, stagingDir);
        await replaceManagedPath(stagingDir, targetDir);
        cleanupStaging = false;
        attemptedCurrentPublish = true;
        await installSymlink(targetDir, current);
        await writeKastConfig(context, current);
        await exportEnvironment(context, current);
    }
    catch (error) {
        if (attemptedCurrentPublish) {
            await restoreCurrentSymlink(current, previousCurrent);
        }
        await restoreTextFile(configPath, previousConfig);
        if (cleanupStaging) {
            await removeManagedPath(stagingDir).catch((cleanupError) => {
                warn(`failed to remove staged runtime ${stagingDir}: ${messageOf(cleanupError)}`);
            });
        }
        throw error;
    }
    info(`Kast installed at ${current}`);
}
async function resolveManifest(context, scratch, targetDir) {
    const manifestPath = (0, node_path_1.join)(scratch, "kast-runtime-manifest.json");
    if (context.manifestUrl) {
        await download(context.manifestUrl, manifestPath, context, {
            label: "runtime manifest",
            authorizationHeader: context.manifestAuthorizationHeader || context.authorizationHeader,
        });
    }
    else {
        await (0, promises_1.copyFile)((0, node_path_1.join)(targetDir, "kast-runtime-manifest.json"), manifestPath).catch(() => {
            throw new Error("kast-runtime-manifest.json is required as manifest-url or inside the runtime artifact");
        });
    }
    return JSON.parse(await (0, promises_1.readFile)(manifestPath, "utf8"));
}
function validateManifest(manifest, version, artifactDigest) {
    if (!isRecord(manifest)) {
        throw new Error("runtime manifest must be a JSON object");
    }
    const allowedFields = new Set(REQUIRED_MANIFEST_FIELDS);
    const unexpected = Object.keys(manifest).filter((key) => !allowedFields.has(key));
    if (unexpected.length > 0) {
        throw new Error(`runtime manifest contains unsupported field(s): ${unexpected.join(", ")}`);
    }
    for (const key of REQUIRED_MANIFEST_FIELDS) {
        if (!(key in manifest)) {
            throw new Error(`runtime manifest is missing ${key}`);
        }
    }
    const schemaVersion = manifest.schemaVersion;
    if (!Number.isInteger(schemaVersion)) {
        throw new Error(`runtime manifest schemaVersion must be an integer, got ${String(schemaVersion)}`);
    }
    if (schemaVersion !== 1) {
        throw new Error(`runtime manifest schemaVersion must be 1, got ${schemaVersion}`);
    }
    const kastVersion = stringField(manifest, "kastVersion");
    const kastGitSha = stringField(manifest, "kastGitSha");
    const os = stringField(manifest, "os");
    const arch = stringField(manifest, "arch");
    const javaVersion = stringField(manifest, "javaVersion");
    const intellijBuild = stringField(manifest, "intellijBuild");
    const kotlinPluginVersion = stringField(manifest, "kotlinPluginVersion");
    const kastIndexSchemaVersion = stringField(manifest, "kastIndexSchemaVersion");
    const artifactSha256 = stringField(manifest, "artifactSha256");
    if (normalizeVersion(kastVersion) !== normalizeVersion(version)) {
        throw new Error(`runtime manifest version ${kastVersion} does not match requested ${version}`);
    }
    if (!/^[0-9a-f]{7,40}$/.test(kastGitSha)) {
        throw new Error(`runtime manifest kastGitSha must be 7 to 40 lowercase hexadecimal characters, got ${kastGitSha}`);
    }
    if (os !== "linux" || arch !== "x64") {
        throw new Error(`runtime manifest platform ${os}/${arch} does not match linux/x64`);
    }
    if (!/^[0-9]+$/.test(javaVersion)) {
        throw new Error(`runtime manifest javaVersion must be numeric, got ${javaVersion}`);
    }
    if (!/^[0-9]+$/.test(kastIndexSchemaVersion)) {
        throw new Error(`runtime manifest kastIndexSchemaVersion must be numeric text, got ${kastIndexSchemaVersion}`);
    }
    const manifestDigest = normalizeChecksum(artifactSha256);
    if (manifestDigest !== artifactDigest) {
        throw new Error(`runtime manifest artifactSha256 does not match artifact digest: expected ${artifactDigest}, got ${manifestDigest}`);
    }
    return {
        schemaVersion,
        kastVersion,
        kastGitSha,
        os,
        arch,
        javaVersion,
        intellijBuild,
        kotlinPluginVersion,
        kastIndexSchemaVersion,
        artifactSha256: manifestDigest,
    };
}
function isRecord(value) {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
function stringField(manifest, key) {
    const value = manifest[key];
    if (typeof value !== "string" || value.trim() === "") {
        throw new Error(`runtime manifest ${key} must be a non-empty string`);
    }
    return value.trim();
}
function normalizeVersion(value) {
    return value.trim().replace(/^v/, "");
}
async function installGradleCache(context, scratch) {
    if (!context.gradleCacheUrl) {
        return;
    }
    try {
        const archive = (0, node_path_1.join)(scratch, "gradle-ro-dep-cache.tar.zst");
        await download(context.gradleCacheUrl, archive, context, {
            label: "Gradle read-only cache",
            authorizationHeader: context.gradleCacheAuthorizationHeader || context.authorizationHeader,
        });
        if (context.gradleCacheSha256) {
            await verifyChecksum(archive, context.gradleCacheSha256, "Gradle read-only cache");
        }
        const extractDir = (0, node_path_1.join)(scratch, "gradle-cache-extract");
        await (0, promises_1.mkdir)(extractDir, { recursive: true });
        await assertSafeTarArchive(archive);
        await execFileAsync("tar", ["--zstd", "-xf", archive, "-C", extractDir]);
        await assertNoSymbolicLinks(extractDir);
        const modulesDir = (0, node_path_1.join)(extractDir, "gradle-ro", "modules-2");
        await (0, promises_1.access)(modulesDir).catch(() => {
            throw new Error("Gradle read-only cache archive must contain gradle-ro/modules-2");
        });
        await assertNoGradleCacheMetadata(modulesDir);
        const targetRoot = (0, node_path_1.join)(context.installDir, "cache", "gradle-ro");
        const preparedRoot = (0, node_path_1.join)(scratch, "gradle-cache-prepared");
        await (0, promises_1.rm)(preparedRoot, { recursive: true, force: true });
        await (0, promises_1.mkdir)(preparedRoot, { recursive: true });
        await (0, promises_1.cp)(modulesDir, (0, node_path_1.join)(preparedRoot, "modules-2"), { recursive: true, force: true });
        await installTree(preparedRoot, targetRoot);
        await makeReadOnly(targetRoot);
        await (0, promises_1.mkdir)((0, node_path_1.join)(context.home, ".gradle"), { recursive: true });
        await appendGithubEnv("GRADLE_RO_DEP_CACHE", targetRoot);
        await appendGithubEnv("GRADLE_USER_HOME", (0, node_path_1.join)(context.home, ".gradle"));
        info(`Gradle read-only cache installed at ${targetRoot}`);
    }
    catch (error) {
        const message = `Gradle read-only cache was not installed: ${messageOf(error)}`;
        if (context.failOnCacheMiss) {
            throw new Error(message);
        }
        warn(message);
    }
}
async function findRuntimeRoot(root) {
    const candidates = await findDirectories(root, 3);
    for (const candidate of [root, ...candidates]) {
        try {
            await assertRuntimeRootShape(candidate);
            return candidate;
        }
        catch {
            // Try the next candidate.
        }
    }
    throw new Error("runtime artifact must contain regular files bin/kast, lib/runtime-libs/classpath.txt, idea/modules/module-descriptors.dat, and an idea/ directory");
}
async function assertRuntimeRootShape(candidate) {
    await assertRegularFile((0, node_path_1.join)(candidate, "bin", "kast"), "runtime bin/kast");
    await assertRegularFile((0, node_path_1.join)(candidate, "lib", "runtime-libs", "classpath.txt"), "runtime classpath.txt");
    await assertDirectory((0, node_path_1.join)(candidate, "idea"), "runtime idea directory");
    await assertRegularFile((0, node_path_1.join)(candidate, "idea", "modules", "module-descriptors.dat"), "runtime module descriptors");
}
async function assertRegularFile(path, label) {
    const stats = await (0, promises_1.lstat)(path);
    if (!stats.isFile()) {
        throw new Error(`${label} must be a regular file: ${path}`);
    }
}
async function assertDirectory(path, label) {
    const stats = await (0, promises_1.lstat)(path);
    if (!stats.isDirectory()) {
        throw new Error(`${label} must be a directory: ${path}`);
    }
}
async function assertSafeTarArchive(archive) {
    const { stdout: members } = await execFileAsync("tar", ["--zstd", "-tf", archive], {
        maxBuffer: 10 * 1024 * 1024,
    });
    for (const rawMember of members.split(/\r?\n/)) {
        const member = rawMember.trim();
        if (!member)
            continue;
        if (isUnsafeArchiveMember(member)) {
            throw new Error(`unsafe archive member: ${member}`);
        }
    }
    const { stdout: metadata } = await execFileAsync("tar", ["--zstd", "-tvf", archive], {
        maxBuffer: 10 * 1024 * 1024,
    });
    for (const rawLine of metadata.split(/\r?\n/)) {
        const line = rawLine.trimStart();
        if (!line)
            continue;
        const type = line[0];
        if (type !== "-" && type !== "d") {
            throw new Error(`unsafe archive member type '${type}' in: ${line}`);
        }
    }
}
function isUnsafeArchiveMember(member) {
    const normalized = member.replace(/\\/g, "/");
    if (normalized.startsWith("/")) {
        return true;
    }
    return normalized.split("/").some((segment) => segment === "..");
}
async function assertNoSymbolicLinks(root) {
    const resolvedRoot = (0, node_path_1.resolve)(root);
    async function visit(directory) {
        const entries = await (0, promises_1.readdir)(directory, { withFileTypes: true });
        for (const entry of entries) {
            const child = (0, node_path_1.join)(directory, entry.name);
            const stats = await (0, promises_1.lstat)(child);
            const relativeChild = child.startsWith(`${resolvedRoot}/`)
                ? child.slice(resolvedRoot.length + 1)
                : child;
            if (stats.isSymbolicLink()) {
                const target = await (0, promises_1.readlink)(child).catch(() => "<unreadable>");
                throw new Error(`unsafe symbolic link in archive: ${relativeChild} -> ${target}`);
            }
            if (stats.isDirectory()) {
                await visit(child);
            }
        }
    }
    await visit(resolvedRoot);
}
async function assertNoGradleCacheMetadata(root) {
    async function visit(directory) {
        const entries = await (0, promises_1.readdir)(directory, { withFileTypes: true });
        for (const entry of entries) {
            const child = (0, node_path_1.join)(directory, entry.name);
            if (entry.name.endsWith(".lock") || entry.name === "gc.properties") {
                throw new Error(`Gradle read-only cache archive contains mutable metadata: ${child}`);
            }
            if (entry.isDirectory()) {
                await visit(child);
            }
        }
    }
    await visit(root);
}
async function findDirectories(root, maxDepth) {
    const result = [];
    async function visit(directory, depth) {
        if (depth > maxDepth)
            return;
        const entries = await (0, promises_1.readdir)(directory, { withFileTypes: true });
        for (const entry of entries) {
            if (!entry.isDirectory())
                continue;
            const child = (0, node_path_1.join)(directory, entry.name);
            result.push(child);
            await visit(child, depth + 1);
        }
    }
    await visit(root, 1);
    return result;
}
async function writeKastConfig(context, current) {
    const configPath = kastConfigPath(context);
    const runtimeLibs = (0, node_path_1.join)(current, "lib", "runtime-libs");
    const ideaHome = (0, node_path_1.join)(current, "idea");
    const binaryPath = (0, node_path_1.join)(current, "bin", "kast");
    await (0, promises_1.mkdir)((0, node_path_1.dirname)(configPath), { recursive: true });
    const content = `[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[paths]
installRoot = ${tomlString(current)}
binDir = ${tomlString((0, node_path_1.join)(current, "bin"))}
libDir = ${tomlString((0, node_path_1.join)(current, "lib"))}
cacheDir = ${tomlString(context.cacheHome)}
logsDir = ${tomlString((0, node_path_1.join)(context.cacheHome, "logs"))}
descriptorDir = ${tomlString((0, node_path_1.join)(context.cacheHome, "workspaces"))}
socketDir = ${tomlString((0, node_path_1.join)(context.cacheHome, "workspaces"))}

[runtime]
defaultBackend = "headless"

[backends.headless]
enabled = true
runtimeLibsDir = ${tomlString(runtimeLibs)}
ideaHome = ${tomlString(ideaHome)}

[backends.idea]
enabled = false

[cli]
binaryPath = ${tomlString(binaryPath)}

[install]
version = ${tomlString(normalizeVersion(context.version))}
backendVersion = ${tomlString(normalizeVersion(context.version))}
installedAt = "setup-kast:${context.version}"
platform = "linux-x64"
components = ["cli", "headless-backend", "config"]
managedPaths = ["bin", "lib", "idea", "plugins", "kast-runtime-manifest.json"]
shellRcPatches = []
repos = []
schemaVersion = 3

[[install.backends]]
name = "headless"
version = ${tomlString(normalizeVersion(context.version))}
installDir = ${tomlString(current)}
runtimeLibsDir = ${tomlString(runtimeLibs)}
ideaHome = ${tomlString(ideaHome)}
`;
    await (0, promises_1.writeFile)(configPath, content, "utf8");
}
function kastConfigPath(context) {
    return (0, node_path_1.join)(context.configHome, "config.toml");
}
async function readOptionalTextFile(path) {
    try {
        return { exists: true, content: await (0, promises_1.readFile)(path, "utf8") };
    }
    catch {
        return { exists: false, content: "" };
    }
}
async function restoreTextFile(path, previous) {
    if (previous.exists) {
        await (0, promises_1.mkdir)((0, node_path_1.dirname)(path), { recursive: true });
        await (0, promises_1.writeFile)(path, previous.content, "utf8");
    }
    else {
        await (0, promises_1.rm)(path, { force: true });
    }
}
async function readCurrentSymlink(path) {
    try {
        const stats = await (0, promises_1.lstat)(path);
        if (!stats.isSymbolicLink()) {
            throw new Error(`current install path exists but is not a symlink: ${path}`);
        }
        return { exists: true, target: await (0, promises_1.readlink)(path) };
    }
    catch (error) {
        if (error.code === "ENOENT") {
            return { exists: false };
        }
        throw error;
    }
}
async function restoreCurrentSymlink(path, previous) {
    if (previous.exists) {
        await installSymlink(previous.target, path);
    }
    else {
        await removeManagedPath(path);
    }
}
async function installTree(source, target) {
    if (await canWriteManagedPath((0, node_path_1.dirname)(target))) {
        await makeWritable(target).catch(() => undefined);
        await (0, promises_1.rm)(target, { recursive: true, force: true });
        await (0, promises_1.mkdir)((0, node_path_1.dirname)(target), { recursive: true });
        await (0, promises_1.cp)(source, target, { recursive: true, force: true });
        return;
    }
    await requireSudo();
    await execFileAsync("sudo", ["rm", "-rf", target]);
    await execFileAsync("sudo", ["mkdir", "-p", (0, node_path_1.dirname)(target)]);
    await execFileAsync("sudo", ["cp", "-a", source, target]);
}
async function replaceManagedPath(source, target) {
    const parent = (0, node_path_1.dirname)(target);
    const backup = (0, node_path_1.join)(parent, `.setup-kast-backup-${process.pid}-${Date.now()}`);
    const targetExists = await pathExists(target);
    if (await canWriteManagedPath(parent)) {
        await (0, promises_1.rm)(backup, { recursive: true, force: true });
        let backupCreated = false;
        if (targetExists) {
            await makeWritable(target).catch(() => undefined);
            await (0, promises_1.rename)(target, backup);
            backupCreated = true;
        }
        try {
            await (0, promises_1.rename)(source, target);
        }
        catch (error) {
            if (backupCreated) {
                await (0, promises_1.rm)(target, { recursive: true, force: true }).catch(() => undefined);
                await (0, promises_1.rename)(backup, target).catch((restoreError) => {
                    warn(`failed to restore previous install ${target}: ${messageOf(restoreError)}`);
                });
            }
            throw error;
        }
        if (backupCreated) {
            await makeWritable(backup).catch(() => undefined);
            await (0, promises_1.rm)(backup, { recursive: true, force: true }).catch((cleanupError) => {
                warn(`failed to remove previous install backup ${backup}: ${messageOf(cleanupError)}`);
            });
        }
        return;
    }
    await requireSudo();
    await execFileAsync("sudo", ["mkdir", "-p", parent]);
    await execFileAsync("sudo", ["rm", "-rf", backup]);
    let backupCreated = false;
    if (targetExists) {
        await execFileAsync("sudo", ["mv", target, backup]);
        backupCreated = true;
    }
    try {
        await execFileAsync("sudo", ["mv", source, target]);
    }
    catch (error) {
        if (backupCreated) {
            await execFileAsync("sudo", ["rm", "-rf", target]).catch(() => undefined);
            await execFileAsync("sudo", ["mv", backup, target]).catch((restoreError) => {
                warn(`failed to restore previous install ${target}: ${messageOf(restoreError)}`);
            });
        }
        throw error;
    }
    if (backupCreated) {
        await execFileAsync("sudo", ["rm", "-rf", backup]).catch((cleanupError) => {
            warn(`failed to remove previous install backup ${backup}: ${messageOf(cleanupError)}`);
        });
    }
}
async function removeManagedPath(target) {
    if (await canWriteManagedPath((0, node_path_1.dirname)(target))) {
        await (0, promises_1.rm)(target, { force: true, recursive: true });
        return;
    }
    await requireSudo();
    await execFileAsync("sudo", ["rm", "-rf", target]);
}
async function makeReadOnly(target) {
    if (await pathExists(target)) {
        if (await canWriteManagedPath((0, node_path_1.dirname)(target))) {
            await execFileAsync("chmod", ["-R", "a-w", target]);
            return;
        }
        await requireSudo();
        await execFileAsync("sudo", ["chmod", "-R", "a-w", target]);
    }
}
async function makeWritable(target) {
    if (await pathExists(target)) {
        await execFileAsync("chmod", ["-R", "u+w", target]);
    }
}
async function pathExists(target) {
    try {
        await (0, promises_1.access)(target);
        return true;
    }
    catch {
        return false;
    }
}
async function installSymlink(target, linkPath) {
    if (await canWriteManagedPath((0, node_path_1.dirname)(linkPath))) {
        await (0, promises_1.rm)(linkPath, { force: true, recursive: true });
        await (0, promises_1.symlink)(target, linkPath);
        return;
    }
    await requireSudo();
    await execFileAsync("sudo", ["rm", "-rf", linkPath]);
    await execFileAsync("sudo", ["mkdir", "-p", (0, node_path_1.dirname)(linkPath)]);
    await execFileAsync("sudo", ["ln", "-s", target, linkPath]);
}
async function canWriteManagedPath(directory) {
    try {
        await (0, promises_1.mkdir)(directory, { recursive: true });
        const probe = (0, node_path_1.join)(directory, `.setup-kast-write-test-${process.pid}`);
        await (0, promises_1.writeFile)(probe, "ok", "utf8");
        await (0, promises_1.rm)(probe, { force: true });
        return true;
    }
    catch {
        return false;
    }
}
async function requireSudo() {
    try {
        await execFileAsync("sudo", ["-n", "true"]);
    }
    catch {
        throw new Error("install-dir is not writable and passwordless sudo is unavailable");
    }
}
function tomlString(value) {
    return JSON.stringify(value);
}
async function exportEnvironment(context, current) {
    await (0, promises_1.mkdir)(context.cacheHome, { recursive: true });
    await appendGithubPath((0, node_path_1.join)(current, "bin"));
    await appendGithubEnv("KAST_HOME", current);
    await appendGithubEnv("KAST_CACHE_HOME", context.cacheHome);
    await appendGithubEnv("KAST_CONFIG_HOME", context.configHome);
}
async function runKastChecks(context, current) {
    const env = {
        ...process.env,
        PATH: `${(0, node_path_1.join)(current, "bin")}:${process.env.PATH || ""}`,
        KAST_HOME: current,
        KAST_CACHE_HOME: context.cacheHome,
        KAST_CONFIG_HOME: context.configHome,
    };
    await execFileAsync((0, node_path_1.join)(current, "bin", "kast"), ["--version"], { env });
    try {
        await execFileAsync((0, node_path_1.join)(current, "bin", "kast"), ["doctor"], { env });
    }
    catch (error) {
        if (context.strict) {
            throw error;
        }
        warn(`kast doctor failed: ${messageOf(error)}`);
    }
}
async function download(source, destination, context, options) {
    let lastError;
    for (let attempt = 1; attempt <= context.downloadAttempts; attempt += 1) {
        try {
            await downloadOnce(source, destination, context.downloadTimeoutMs, options);
            return;
        }
        catch (error) {
            lastError = error;
            if (attempt === context.downloadAttempts) {
                break;
            }
            warn(`download attempt ${attempt} failed for ${options.label}: ${messageOf(error)}`);
            await sleep(context.downloadRetryDelayMs);
        }
    }
    throw new Error(`download failed for ${options.label} after ${context.downloadAttempts} attempts: ${messageOf(lastError)}`);
}
async function downloadOnce(source, destination, timeoutMs, options) {
    await (0, promises_1.mkdir)((0, node_path_1.dirname)(destination), { recursive: true });
    if (source.startsWith("file://")) {
        await (0, promises_1.copyFile)((0, node_url_1.fileURLToPath)(source), destination);
        return;
    }
    if (/^\//.test(source)) {
        await (0, promises_1.copyFile)(source, destination);
        return;
    }
    let url;
    try {
        url = new URL(source);
    }
    catch {
        throw new Error(`invalid URL for ${options.label}`);
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") {
        throw new Error(`unsupported URL protocol for ${options.label}: ${url.protocol}`);
    }
    const headers = options.authorizationHeader
        ? { Authorization: options.authorizationHeader }
        : undefined;
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(timeoutMs) });
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }
    if (!response.body) {
        throw new Error("HTTP response did not include a body");
    }
    const temporaryDestination = `${destination}.download-${process.pid}-${Date.now()}`;
    try {
        const body = response.body;
        await (0, promises_2.pipeline)(node_stream_1.Readable.fromWeb(body), (0, node_fs_1.createWriteStream)(temporaryDestination, { flags: "w" }));
        await (0, promises_1.rename)(temporaryDestination, destination);
    }
    catch (error) {
        await (0, promises_1.rm)(temporaryDestination, { force: true });
        throw error;
    }
}
async function sleep(milliseconds) {
    if (milliseconds <= 0)
        return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}
async function verifyChecksum(path, expected, label) {
    const actual = await sha256File(path);
    const normalized = normalizeChecksum(expected);
    if (actual !== normalized) {
        throw new Error(`${label} checksum mismatch: expected ${normalized}, got ${actual}`);
    }
    return actual;
}
async function sha256File(path) {
    const hash = (0, node_crypto_1.createHash)("sha256");
    for await (const chunk of (0, node_fs_1.createReadStream)(path)) {
        hash.update(chunk);
    }
    return hash.digest("hex");
}
function normalizeChecksum(value) {
    const token = value.trim().replace(/^sha256:/i, "").split(/\s+/)[0]?.toLowerCase() || "";
    if (!/^[a-f0-9]{64}$/.test(token)) {
        throw new Error(`invalid SHA-256 digest: ${value}`);
    }
    return token;
}
async function appendGithubEnv(name, value) {
    if (!/^[A-Z_][A-Z0-9_]*$/.test(name)) {
        throw new Error(`invalid GITHUB_ENV variable name: ${name}`);
    }
    const target = process.env.GITHUB_ENV;
    if (!target) {
        warn(`GITHUB_ENV is unset; cannot persist ${name}`);
        return;
    }
    const delimiter = githubEnvDelimiter(name, value);
    await appendLine(target, `${name}<<${delimiter}\n${value}\n${delimiter}`);
}
async function appendGithubPath(value) {
    assertSingleLineValue("GITHUB_PATH entry", value);
    const target = process.env.GITHUB_PATH;
    if (!target) {
        warn("GITHUB_PATH is unset; cannot persist PATH update");
        return;
    }
    await appendLine(target, value);
}
function githubEnvDelimiter(name, value) {
    let attempt = 0;
    while (true) {
        const digest = (0, node_crypto_1.createHash)("sha256")
            .update(name)
            .update("\0")
            .update(value)
            .update("\0")
            .update(String(attempt))
            .digest("hex");
        const delimiter = `setup_kast_${digest}`;
        if (!value.includes(delimiter)) {
            return delimiter;
        }
        attempt += 1;
    }
}
async function appendLine(path, line) {
    await (0, promises_1.mkdir)((0, node_path_1.dirname)(path), { recursive: true });
    await (0, promises_1.writeFile)(path, `${line}\n`, { flag: "a", encoding: "utf8" });
}
async function makeScratch() {
    const root = (0, node_path_1.resolve)((0, node_os_1.tmpdir)(), `setup-kast-${process.pid}-${Date.now()}`);
    await (0, promises_1.mkdir)(root, { recursive: true });
    return root;
}
function info(message) {
    process.stderr.write(`${message}\n`);
}
function warn(message) {
    process.stderr.write(`::warning::${message}\n`);
}
function messageOf(error) {
    if (error instanceof Error) {
        const details = [error.message];
        const commandError = error;
        const code = commandError.code;
        if (code !== undefined && code !== null) {
            details.push(`exit code: ${String(code)}`);
        }
        const stderr = outputSnippet(commandError.stderr);
        if (stderr) {
            details.push(`stderr:\n${stderr}`);
        }
        const stdout = outputSnippet(commandError.stdout);
        if (stdout) {
            details.push(`stdout:\n${stdout}`);
        }
        return details.join("\n");
    }
    return String(error);
}
function outputSnippet(value) {
    if (value === undefined || value === null) {
        return "";
    }
    const text = Buffer.isBuffer(value) ? value.toString("utf8") : String(value);
    const trimmed = text.trim();
    if (!trimmed) {
        return "";
    }
    const limit = 4000;
    if (trimmed.length <= limit) {
        return trimmed;
    }
    return `${trimmed.slice(0, limit)}\n... truncated ...`;
}
main().catch((error) => {
    process.stderr.write(`setup-kast failed: ${messageOf(error)}\n`);
    process.exitCode = 1;
});
