#!/usr/bin/env python3
"""Configure only the owned Gradle directory using the current approved proxy."""
import os
from pathlib import Path
import shlex
from urllib.parse import urlparse

root = Path(os.environ["PI_EVAL_ROOT"]).resolve()
if not (root / ".pi-eval-owned").is_file():
    raise SystemExit("Owned runtime required")
proxy = urlparse(os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or "")
if proxy.username or proxy.password:
    raise SystemExit("Credential-bearing proxy requires its supported platform integration")
properties = {}
if proxy.hostname:
    for protocol in ("http", "https"):
        properties[f"systemProp.{protocol}.proxyHost"] = proxy.hostname
        properties[f"systemProp.{protocol}.proxyPort"] = str(proxy.port or 80)
    properties["systemProp.http.nonProxyHosts"] = "localhost|127.*|[::1]"
truststore = Path(os.environ.get("PI_EVAL_JAVA_TRUSTSTORE", "/etc/ssl/certs/java/cacerts"))
if truststore.is_file():
    properties["systemProp.javax.net.ssl.trustStore"] = str(truststore)
target = root / "gradle/gradle.properties"
marker = "# Owned by Kast cloud profile; no credentials\n"
if target.exists() and not target.read_text().startswith(marker):
    raise SystemExit("Refusing to replace an unowned Gradle properties file")
target.write_text(marker + "".join(f"{key}={value}\n" for key, value in properties.items()))
options = shlex.join(["-D" + key.removeprefix("systemProp.") + "=" + value for key, value in properties.items()])
(root / "gradle-runtime.sh").write_text('export GRADLE_OPTS="$PI_EVAL_GRADLE_BASE_OPTS "' + shlex.quote(options) + "\n")
