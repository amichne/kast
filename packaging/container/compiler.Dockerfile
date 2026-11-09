# syntax=docker/dockerfile:1
FROM debian:13.6-slim@sha256:d7e12182ce18b85b93007c1dedf31f2d29e01ccf3182cc4017c709b6259bc132
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates python3 build-essential zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
COPY native-inputs.tar.gz /native-inputs.tar.gz
COPY container_compile.py /container_compile.py
RUN python3 /container_compile.py
