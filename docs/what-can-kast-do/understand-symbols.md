---
title: Understand symbols
description: Resolve symbol identity, browse file outlines, search the
  workspace by name, and find concrete implementations.
icon: lucide/search
---

# Understand symbols

This page covers the operations that answer identity questions about
your Kotlin code. Each operation takes a position or a name and returns
structured JSON describing exactly what the compiler knows about the
symbol at that location. Together they let you resolve a symbol to its
unique identity, browse the declaration tree of a file, discover
symbols across the workspace by name, and enumerate every concrete
implementation of an interface or abstract class.

## Resolve a symbol

When you point kast at a byte offset in a Kotlin file, it doesn't grep
for a name. It resolves the exact declaration the compiler sees at that
position and returns three fields that uniquely identify the symbol:
`fqName`, `kind`, and `location`. Every other field — return type,
parameters, containing declaration — is context that builds on that
identity triple.

Position-based resolution is what makes kast different from text
matching. Two functions named `process` in different classes produce
two distinct `fqName` values. Overloads at the same call site resolve
to the correct overload based on the compiler's type analysis, not a
string match.

=== "CLI"

    ```console title="Resolve the symbol at a specific file position"
    kast resolve \
      --workspace-root=/app \
      --file-path=/app/src/main/kotlin/com/example/OrderService.kt \
      --offset=142
    ```

=== "JSON-RPC"

    ```json title="JSON-RPC request"
    {
      "method": "resolve",
      "params": {
        "position": {
          "filePath": "/app/src/main/kotlin/com/example/OrderService.kt",
          "offset": 142
        }
      },
      "id": 1, "jsonrpc": "2.0"
    }
    ```

=== "Ask your agent"

    ```text title="Natural language prompt"
    Use kast to resolve the processOrder function on OrderService.
    Tell me its fully qualified name, return type, and parameters.
    ```

```json hl_lines="3-5" title="Response — the identity triple"
{
  "symbol": {
    "fqName": "com.example.OrderService.processOrder",
    "kind": "FUNCTION",
    "location": {
      "filePath": "/app/src/.../OrderService.kt",
      "startLine": 47,
      "preview": "processOrder"
    },
    "returnType": "Order",
    "parameters": [
      { "name": "cart", "type": "Cart" }
    ],
    "containingDeclaration": "com.example.OrderService"
  }
}
```

The `--offset` value is a zero-based byte offset into the file. You
can get it from your editor's cursor position or compute it from a
line and column. kast resolves through references, so pointing at a
call site returns the declaration the call resolves to, not the call
itself.

## Outline a file

The `outline` command returns a nested declaration tree for a single
Kotlin file. Each node carries the same `Symbol` shape you see in a
`resolve` response, and child declarations nest inside their parent.
The tree excludes function parameters, anonymous elements, and local
declarations — it shows only the named declarations that form the
file's public and internal structure.

Use `outline` when you need a quick map of a file's contents without
reading the full source. Agents use it to decide which offset to pass
to `resolve` or `references`.

=== "CLI"

    ```console title="Get the declaration tree for a file"
    kast outline \
      --workspace-root=/app \
      --file-path=/app/src/main/kotlin/com/example/OrderService.kt
    ```

=== "JSON-RPC"

    ```json title="JSON-RPC request"
    {
      "method": "file-outline",
      "params": {
        "filePath": "/app/src/main/kotlin/com/example/OrderService.kt"
      },
      "id": 1, "jsonrpc": "2.0"
    }
    ```

=== "Ask your agent"

    ```text title="Natural language prompt"
    Use kast to outline OrderService.kt. Show me every class,
    function, and property declared in the file.
    ```

```json hl_lines="4-5 12-13" title="Response — nested declaration tree"
{
  "symbols": [
    {
      "symbol": {
        "fqName": "com.example.OrderService",
        "kind": "CLASS",
        "location": {
          "filePath": "/app/src/.../OrderService.kt",
          "startLine": 12,
          "preview": "class OrderService"
        }
      },
      "children": [
        {
          "symbol": {
            "fqName": "com.example.OrderService.processOrder",
            "kind": "FUNCTION",
            "location": {
              "filePath": "/app/src/.../OrderService.kt",
              "startLine": 47,
              "preview": "processOrder"
            },
            "returnType": "Order",
            "parameters": [
              { "name": "cart", "type": "Cart" }
            ]
          },
          "children": []
        },
        {
          "symbol": {
            "fqName": "com.example.OrderService.orderRepository",
            "kind": "PROPERTY",
            "location": {
              "filePath": "/app/src/.../OrderService.kt",
              "startLine": 14,
              "preview": "val orderRepository"
            },
            "type": "OrderRepository"
          },
          "children": []
        }
      ]
    }
  ]
}
```

The `children` array nests recursively. A top-level class contains its
member functions and properties, an inner class contains its own
members, and so on. Empty `children` means the declaration has no
nested named declarations.

## Search for workspace symbols

The `workspace-symbol` command finds declarations across your entire
workspace by name. By default it runs a substring match against symbol
names. You can narrow results with `--kind` to filter by symbol kind,
or switch to `--regex=true` for regular expression patterns.

Use this when you know a name (or part of one) but don't know which
file contains it. Agents use it as a discovery step before calling
`resolve` on a specific match.

=== "CLI"

    ```console title="Find all classes matching a pattern"
    kast workspace-symbol \
      --workspace-root=/app \
      --pattern=OrderService
    ```

    ```console title="Regex search filtered to classes"
    kast workspace-symbol \
      --workspace-root=/app \
      --pattern=".*Service" \
      --regex=true \
      --kind=CLASS
    ```

=== "JSON-RPC"

    ```json title="JSON-RPC request"
    {
      "method": "workspace-symbol",
      "params": {
        "pattern": ".*Service",
        "regex": true,
        "kind": "CLASS",
        "maxResults": 50
      },
      "id": 1, "jsonrpc": "2.0"
    }
    ```

=== "Ask your agent"

    ```text title="Natural language prompt"
    Use kast to find every class in the workspace whose name ends
    with "Service". List their fully qualified names and locations.
    ```

```json hl_lines="5 13 17-18" title="Response — matched symbols with pagination"
{
  "symbols": [
    {
      "fqName": "com.example.OrderService",
      "kind": "CLASS",
      "location": {
        "filePath": "/app/src/.../OrderService.kt",
        "startLine": 12,
        "preview": "class OrderService"
      }
    },
    {
      "fqName": "com.example.CartService",
      "kind": "CLASS",
      "location": {
        "filePath": "/app/src/.../CartService.kt",
        "startLine": 8,
        "preview": "class CartService"
      }
    }
  ],
  "page": {
    "truncated": false
  }
}
```

When results exceed `maxResults`, the `page` object reports
`"truncated": true` and includes a `nextPageToken` you can pass in a
follow-up request. Always check `page.truncated` before assuming you
have every match.

## Find implementations

The `implementations` command takes a position on an interface or
abstract class and returns every concrete implementation in the
workspace. Each result carries its `supertypes` chain so you can see
the full inheritance path, and the `exhaustive` flag tells you whether
kast found every implementation within the result cap.

=== "CLI"

    ```console title="Find all implementations of an interface"
    kast implementations \
      --workspace-root=/app \
      --file-path=/app/src/main/kotlin/sample/Greeter.kt \
      --offset=28
    ```

=== "JSON-RPC"

    ```json title="JSON-RPC request"
    {
      "method": "implementations",
      "params": {
        "position": {
          "filePath": "/app/src/main/kotlin/sample/Greeter.kt",
          "offset": 28
        },
        "maxResults": 100
      },
      "id": 1, "jsonrpc": "2.0"
    }
    ```

=== "Ask your agent"

    ```text title="Natural language prompt"
    Use kast to find every class that implements the Greeter
    interface. Show their fully qualified names and supertype chains.
    ```

```json hl_lines="3-4 9 12" title="Response — implementations with supertype chains"
{
  "declaration": {
    "fqName": "sample.Greeter",
    "kind": "INTERFACE"
  },
  "implementations": [
    {
      "fqName": "sample.LoudGreeter",
      "kind": "CLASS",
      "supertypes": ["sample.FriendlyGreeter"]
    }
  ],
  "exhaustive": true
}
```

When `exhaustive` is `true`, kast found every implementation within
the `maxResults` limit. When it's `false`, more implementations exist
than the cap allowed — increase `maxResults` or paginate to get the
full set. The `supertypes` array shows the immediate supertypes of
each implementation, which is useful for understanding intermediate
abstract classes or mixins in the inheritance chain.

## Next steps

Now that you can identify symbols and browse the declaration
landscape, move on to tracing how those symbols are used and changing
them safely.

- [Trace usage](trace-usage.md) — find every reference to a symbol
  and walk call hierarchies with exhaustiveness proof.
- [Refactor safely](refactor-safely.md) — plan and apply renames with
  hash-based conflict detection.
