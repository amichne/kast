#!/usr/bin/env python3
"""Installed private-CA proof for independent Gradle distribution and daemon network paths."""
import argparse
from contextlib import contextmanager, ExitStack
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import threading
import tempfile
import time
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


@contextmanager
def owned_runtime(kast, root, environment, evidence):
    """Always release the case's exact-root runtime, preserving cleanup evidence."""
    try:
        yield
    finally:
        result = subprocess.run([str(kast), "stop"], cwd=root, env=environment,
                                capture_output=True, text=True, timeout=60)
        (evidence / "stop.stdout.json").write_text(result.stdout)
        (evidence / "stop.stderr.log").write_text(result.stderr)
        require(result.returncode == 0, "owned runtime stop failed")
        try:
            document = json.loads(result.stdout)
        except ValueError as error:
            raise RuntimeError("owned runtime stop omitted JSON evidence") from error
        require(document.get("status") == "complete" and document.get("runtime") == "stopped",
                "owned runtime stop did not prove a stopped runtime")


def require_repository_tls_failure(import_log, endpoint):
    """Require the private dependency's failed HTTPS fetch in Gradle import output."""
    require("Could not resolve enterprise:proof:1.0" in import_log,
            "repository failure lacks the private dependency coordinate in Gradle import evidence")
    require(endpoint + "/maven/enterprise/proof/1.0/proof-1.0." in import_log,
            "repository failure lacks the private dependency URL in Gradle import evidence")
    require(any(marker in import_log for marker in
                ("SSLHandshakeException", "PKIX", "unable to find valid certification path")),
            "repository failure lacks TLS evidence at the Gradle import boundary")


def command(argv, cwd=None):
    result = subprocess.run([str(value) for value in argv], cwd=cwd, capture_output=True, text=True, timeout=180)
    if result.returncode:
        raise RuntimeError(f"fixture command failed: {argv[0]}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kast", required=True, type=Path)
    parser.add_argument("--gradle", required=True, type=Path, help="Existing unpacked Gradle distribution")
    parser.add_argument("--jbr", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    base = args.evidence.resolve()
    web = base / "https"
    web.mkdir(exist_ok=True)
    with ExitStack() as cleanup:
        secrets = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix="private-", dir=base)))
        command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                 "-subj", "/CN=Kast acceptance root", "-keyout", secrets / "ca.key", "-out", secrets / "ca.pem"])
        command(["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
                 "-keyout", secrets / "server.key", "-out", secrets / "server.csr"])
        (secrets / "extensions").write_text("subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n")
        command(["openssl", "x509", "-req", "-in", secrets / "server.csr", "-CA", secrets / "ca.pem",
                 "-CAkey", secrets / "ca.key", "-CAcreateserial", "-days", "2", "-extfile", secrets / "extensions",
                 "-out", secrets / "server.pem"])
        for path in secrets.iterdir():
            path.chmod(0o600)
        donor = base / "donor/lib/security"
        donor.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(args.jbr / "lib/security/cacerts", donor / "cacerts")
        command([args.jbr / "bin/keytool", "-importcert", "-noprompt", "-alias", "kast-private-ca",
                 "-file", secrets / "ca.pem", "-keystore", donor / "cacerts", "-storepass", "changeit"])
        target_digest = hashlib.sha256((args.jbr / "lib/security/cacerts").read_bytes()).hexdigest()
        donor_digest = hashlib.sha256((donor / "cacerts").read_bytes()).hexdigest()
        version = args.gradle.name.removeprefix("gradle-")
        archive = web / f"gradle-{version}-bin.zip"
        if not archive.exists():
            shutil.make_archive(str(archive.with_suffix("")), "zip", args.gradle.parent, args.gradle.name)
        repository = web / "maven/enterprise/proof/1.0"
        repository.mkdir(parents=True, exist_ok=True)
        java = base / "java/enterprise"
        java.mkdir(parents=True, exist_ok=True)
        (java / "Proof.java").write_text("package enterprise; public final class Proof { public static int value() { return 42; } }\n")
        command([args.jbr / "bin/javac", "--release", "17", java / "Proof.java"])
        with zipfile.ZipFile(repository / "proof-1.0.jar", "w") as jar:
            jar.write(java / "Proof.class", "enterprise/Proof.class")
        (repository / "proof-1.0.pom").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>enterprise</groupId><artifactId>proof</artifactId><version>1.0</version></project>')
        requests = []

        class Handler(http.server.SimpleHTTPRequestHandler):
            def __init__(self, *values, **options):
                super().__init__(*values, directory=str(web), **options)

            def log_message(self, format, *values):
                requests.append({"path": self.path, "method": self.command, "status": values[1] if len(values) > 1 else ""})

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cleanup.callback(server.server_close)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(secrets / "server.pem", secrets / "server.key")
        server.socket = context.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        cleanup.callback(server.shutdown)
        endpoint = f"https://localhost:{server.server_port}"
        cases = []
        for name, has_donor, distribution in (
            ("trusted", True, endpoint + "/" + archive.name),
            ("untrusted-distribution", False, endpoint + "/" + archive.name),
            ("untrusted-repository", False, archive.as_uri()),
        ):
            case = base / name
            root = case / "workspace"
            root.mkdir(parents=True, exist_ok=False)
            (root / "settings.gradle.kts").write_text('rootProject.name = "enterprise-tls"\n')
            (root / "build.gradle.kts").write_text('plugins { java }\n'
                + f'repositories {{ maven {{ url = uri("{endpoint}/maven") }} }}\n'
                + 'dependencies { implementation("enterprise:proof:1.0") }\n'
                + 'sourceSets.main { java.setSrcDirs(listOf("src/main/kotlin")) }\n'
                + 'check(System.getProperty("kast.hostile.ambient") == null) { "ambient injection reached daemon" }\n'
                + 'configurations.compileClasspath.get().resolve()\n')
            source = root / "src/main/kotlin/TlsFixture.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package tls.fixture\nimport enterprise.Proof\nfun tlsProof(): Int = Proof.value()\n")
            wrapper = root / "gradle/wrapper"
            wrapper.mkdir(parents=True)
            (wrapper / "gradle-wrapper.properties").write_text(f"distributionUrl={distribution}\n"
                + f"distributionSha256Sum={hashlib.sha256(archive.read_bytes()).hexdigest()}\n")
            gradle_cache = case / "gradle-home"
            gradle_cache.mkdir()
            require(not list(gradle_cache.iterdir()), "Gradle cache was not cold")
            env = dict(os.environ)
            for key in ("KAST_NETWORK_CONFIG", "KAST_IDE_CONFIG_HOME", "KAST_TRUST_DONOR_JAVA_HOME", "JAVA_HOME"):
                env.pop(key, None)
            env.update(GRADLE_USER_HOME=str(gradle_cache), KAST_CACHE_ROOT=str(case / "caches"),
                KAST_RUNTIME_DIRECTORY=str(case / "endpoints"), KAST_RUNTIME_STORE=str(case / "store"))
            if has_donor:
                env["KAST_TRUST_DONOR_JAVA_HOME"] = str(donor.parents[1])
            # These would break bootstrap if ambient JVM injection reached the sidecar.
            env.update(JAVA_TOOL_OPTIONS="-Dkast.hostile.ambient=true", _JAVA_OPTIONS="-Dkast.hostile.ambient=true",
                       JDK_JAVA_OPTIONS="-Dkast.hostile.ambient=true")
            start = time.monotonic()
            with owned_runtime(args.kast, root, env, case):
                result = subprocess.run([str(args.kast), "symbol", "discover", "--query", "tlsProof", "--limit", "5"],
                                        cwd=root, env=env, capture_output=True, text=True, timeout=600)
                (case / "stdout.json").write_text(result.stdout)
                (case / "stderr.log").write_text(result.stderr)
                payload = json.loads(result.stdout) if result.stdout else {}
                successful = result.returncode == 0 and payload.get("status") == "complete" and bool(payload.get("items"))
                logs = "\n".join(path.read_text(errors="replace") for path in (case / "caches").rglob("*.log"))
                if has_donor:
                    require(successful, f"trusted installed semantic request failed; inspect {case}")
                    require(any(item["path"].endswith(archive.name) and item["method"] == "GET" and item["status"] == "200" for item in requests), "trusted distribution was not fetched successfully")
                    require(any(item["path"].endswith("proof-1.0.jar") and item["method"] == "GET" and item["status"] == "200" for item in requests), "trusted dependency was not fetched successfully")
                else:
                    require(not successful, f"missing donor manufactured success: {name}")
                    require(any(marker in logs for marker in ("SSLHandshakeException", "PKIX", "unable to find valid certification path")), f"failure lacks TLS evidence: {name}")
                    if name == "untrusted-repository":
                        imports = "\n".join(path.read_text(errors="replace") for path in (case / "caches").glob("*/log/gradle-import.log"))
                        require_repository_tls_failure(imports, endpoint)
                require(not any("Picked up " + key in logs for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")), "ambient JVM options reached an installed consumer")
            cases.append({"case": name, "status": "passed", "elapsedSeconds": round(time.monotonic() - start, 2)})
            print(json.dumps(cases[-1]), flush=True)
        require(target_digest == hashlib.sha256((args.jbr / "lib/security/cacerts").read_bytes()).hexdigest(), "target truststore was modified")
        require(donor_digest == hashlib.sha256((donor / "cacerts").read_bytes()).hexdigest(), "donor truststore was modified")
        (base / "evidence.json").write_text(json.dumps({"status": "passed", "cases": cases, "httpsRequests": requests}, indent=2))


if __name__ == "__main__":
    main()
