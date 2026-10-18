#!/usr/bin/env python3
"""Build prerequisites through the existing approved proxy, including its DNS binding."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
from urllib.parse import urlparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--docker-config", required=True, type=Path, help="New isolated Docker configuration directory")
args = parser.parse_args()
args.docker_config.mkdir(parents=True, exist_ok=True, mode=0o700)
env = {**os.environ, "DOCKER_CONFIG": str(args.docker_config.resolve())}
command = ["docker", "build"]
for lower, upper in (("http_proxy", "HTTP_PROXY"), ("https_proxy", "HTTPS_PROXY")):
    value = os.environ.get(upper) or os.environ.get(lower)
    if value:
        proxy = urlparse(value)
        if proxy.username or proxy.password:
            raise SystemExit("Use the platform's supported credential-bearing proxy integration")
        if proxy.hostname is None:
            raise SystemExit("Invalid proxy hostname")
        address = next(item[4][0] for item in socket.getaddrinfo(proxy.hostname, proxy.port or 80,
                       type=socket.SOCK_STREAM) if item[0] == socket.AF_INET)
        command += ["--add-host", f"{proxy.hostname}:{address}", "--build-arg", lower]
        env[lower] = value
command += ["-t", "kast-cloud-prereqs:local", str(Path(__file__).resolve().parent)]
subprocess.run(command, env=env, check=True)
