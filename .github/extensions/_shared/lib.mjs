import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {dirname, join} from "node:path";

function shadowedExtensionStateFile(repoRoot) {
    const sessionKey = createHash("sha256").update(repoRoot).digest("hex");
    return join(process.env.TMPDIR || "/tmp", `copilot-hook-shadowed-extensions-${sessionKey}.txt`);
}

export function markShadowedExtensionLoaded(repoRoot, extensionId) {
    const stateFile = shadowedExtensionStateFile(repoRoot);
    const parentDir = dirname(stateFile);
    if (!existsSync(parentDir)) {
        mkdirSync(parentDir, {recursive: true});
    }
    const loaded = existsSync(stateFile)
        ? new Set(
            readFileSync(stateFile, "utf8")
                .split("\n")
                .map((entry) => entry.trim())
                .filter(Boolean),
        )
        : new Set();
    loaded.add(extensionId);
    writeFileSync(stateFile, [...loaded].sort().join("\n") + "\n", "utf8");
}
