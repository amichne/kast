# Kast Codex examples

Generated from the exhaustive Rust exposure contract. Replace angle-bracket placeholders with exact values.

## `agent lease acquire`

```console
kast --output toon agent lease acquire --workspace-root <root> --backend <backend>
```

## `agent lease status`

```console
kast --output toon agent lease status --workspace-root <root> --backend <backend> --lease-id <id>
```

## `agent lease release`

```console
kast --output toon agent lease release --workspace-root <root> --backend <backend> --lease-id <id>
```

## `agent workspace-files`

```console
kast --output toon agent workspace-files --workspace-root <root>
```

## `agent symbol`

```console
kast --output toon agent symbol --workspace-root <root> --query <name>
```

## `agent references`

```console
kast --output toon agent references --workspace-root <root> --symbol <fq-name>
```

## `agent callers`

```console
kast --output toon agent callers --workspace-root <root> --symbol <fq-name>
```

## `agent callees`

```console
kast --output toon agent callees --workspace-root <root> --symbol <fq-name>
```

## `agent implementations`

```console
kast --output toon agent implementations --workspace-root <root> --symbol <fq-name>
```

## `agent hierarchy`

```console
kast --output toon agent hierarchy --workspace-root <root> --symbol <fq-name>
```

## `agent impact`

```console
kast --output toon agent impact --workspace-root <root> --symbol <fq-name>
```

## `agent diagnostics`

```console
kast --output toon agent diagnostics --workspace-root <root> --file-path <path>
```

## `agent rename`

```console
kast --output toon agent rename --workspace-root <root> --symbol <fq-name> --new-name <name>
kast --output toon agent rename --workspace-root <root> --symbol <fq-name> --new-name <name> --apply --idempotency-key <key>
```

## `agent add-file`

```console
kast --output toon agent add-file --workspace-root <root> --file-path <path> --content-file <file>
kast --output toon agent add-file --workspace-root <root> --file-path <path> --content-file <file> --apply --idempotency-key <key>
```

## `agent add-declaration`

```console
kast --output toon agent add-declaration --workspace-root <root> --inside-file <path> --at file-bottom --content-file <file>
kast --output toon agent add-declaration --workspace-root <root> --inside-file <path> --at file-bottom --content-file <file> --apply --idempotency-key <key>
```

## `agent add-implementation`

```console
kast --output toon agent add-implementation --workspace-root <root> --inside-file <path> --at file-bottom --content-file <file>
kast --output toon agent add-implementation --workspace-root <root> --inside-file <path> --at file-bottom --content-file <file> --apply --idempotency-key <key>
```

## `agent add-statement`

```console
kast --output toon agent add-statement --workspace-root <root> --inside-scope <fq-name> --at body-end --content-file <file>
kast --output toon agent add-statement --workspace-root <root> --inside-scope <fq-name> --at body-end --content-file <file> --apply --idempotency-key <key>
```

## `agent replace-declaration`

```console
kast --output toon agent replace-declaration --workspace-root <root> --symbol <fq-name> --content-file <file>
kast --output toon agent replace-declaration --workspace-root <root> --symbol <fq-name> --content-file <file> --apply --idempotency-key <key>
```

## `agent operation status`

```console
kast --output toon agent operation status --workspace-root <root> --idempotency-key <key>
```

## `agent operation cancel`

```console
kast --output toon agent operation cancel --workspace-root <root> --operation-id <id>
```
