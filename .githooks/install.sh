#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
hook_dir="$(git rev-parse --git-path hooks)"
destination="${hook_dir}/pre-push"

if [[ -e "${destination}" ]] && ! grep -Fq '# kast-pre-push-dispatch' "${destination}"; then
  echo "kast hook install: existing pre-push hook is not owned by Kast: ${destination}" >&2
  exit 1
fi

mkdir -p "${hook_dir}"
temporary="$(mktemp "${hook_dir}/.pre-push.XXXXXX")"
trap 'rm -f "${temporary}"' EXIT
cat > "${temporary}" <<'HOOK'
#!/usr/bin/env bash
# kast-pre-push-dispatch
set -euo pipefail
repo_root="$(git rev-parse --show-toplevel)"
hook="${repo_root}/.githooks/pre-push"
[[ -x "${hook}" ]] || exit 0
exec "${hook}" "$@"
HOOK
chmod +x "${temporary}"
mv "${temporary}" "${destination}"
echo "kast hook install: ${destination}"
