# syntax=docker/dockerfile:1
FROM debian:13.6-slim@sha256:d7e12182ce18b85b93007c1dedf31f2d29e01ccf3182cc4017c709b6259bc132

# OS packages follow Debian's repository. Record the resulting image ID and dpkg
# inventory; language tools and evaluation packages have independent exact pins.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git python3 tar xz-utils bash build-essential zlib1g-dev \
    zsh shellcheck neovim \
    xvfb xauth dbus-x11 fontconfig libxi6 libxrender1 libxtst6 \
    libgtk-3-0 libnss3 libasound2t64 libgbm1 \
    && rm -rf /var/lib/apt/lists/*

COPY . /opt/kast-cloud
RUN chmod -R a+rX /opt/kast-cloud \
    && useradd --uid 10001 --create-home evaluator \
    && mkdir /runtime /work && chown evaluator:evaluator /runtime /work
USER evaluator
WORKDIR /work
CMD ["python3", "/opt/kast-cloud/bootstrap.py", "--root", "/runtime/pi-eval"]
