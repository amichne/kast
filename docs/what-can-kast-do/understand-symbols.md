---
title: Understand symbols
description: Resolve symbol identity, browse file outlines, search the
  workspace by name, and find concrete implementations.
icon: lucide/search
---

# Understand symbols

These are the operations that answer "what is this?" Each one takes a
position or a name and returns structured JSON about the declaration
the compiler sees there. Together they let you pin a symbol to a
unique identity, walk a file's declaration tree, search the workspace
by name, and list every concrete implementation of an interface.

## Resolve a symbol

Point `kast` at a byte offset in a Kotlin file. It doesn't grep — it
resolves the declaration the compiler sees at that position. Three
fields uniquely identify the symbol: `fqName`, `kind`, `location`.
Everything else (return type, parameters, containing declaration)
hangs off that triple.

This is what makes `kast` different from text matching. Two functions
named `process` in different classes get two different `fqName`s.
Overloads at a call site resolve to the right overload because the
compiler picked it.

=== "CLI"

    ```console title="Resolve the symbol at a specific file position"
    kast resolve \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/com/example/OrderService.kt \
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

`--offset` is a zero-based byte offset. Get it from your editor's
cursor or compute it from a line and column. `kast` resolves through
references — point at a call site and you get the declaration the
call resolves to, not the call itself.

## Outline a file

`outline` returns a nested declaration tree for one file. Each node
is the same `Symbol` shape as `resolve`. Children nest inside their
parent. Function parameters, anonymous elements, and local
declarations are excluded — what you get is the file's named
structure.

Use it for a quick map of a file without reading the source. Agents
use it to pick which offset to feed `resolve` or `references`.

=== "CLI"

    ```console title="Get the declaration tree for a file"
    kast outline \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/com/example/OrderService.kt
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

`children` nests recursively. A class contains its members, an inner
class contains its own, and so on. Empty `children` means no nested
named declarations.

## Search the workspace by name

`workspace-symbol` finds declarations across the workspace by name.
Substring match by default; narrow with `--kind`, switch to regex
with `--regex=true`.

Reach for it when you know the name (or part of it) but not the file.
Agents use it as a discovery step before calling `resolve` on a
specific match.

=== "CLI"

    ```console title="Find all classes matching a pattern"
    kast workspace-symbol \
      --workspace-root=$(pwd) \
      --pattern=OrderService
    ```

    ```console title="Regex search filtered to classes"
    kast workspace-symbol \
      --workspace-root=$(pwd) \
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

When results exceed `maxResults`, `page.truncated` flips to `true`
and you get a `nextPageToken`. Always check `page.truncated` before
claiming you have every match.

## Find implementations

`implementations` takes a position on an interface or abstract class
and returns every concrete implementation in the workspace. Each
result carries its `supertypes` chain so you can see the full
inheritance path. The `exhaustive` flag tells you whether `kast`
found every implementation within the result cap.

=== "CLI"

    ```console title="Find all implementations of an interface"
    kast implementations \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/sample/Greeter.kt \
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

`exhaustive: true` means `kast` found every implementation within
the `maxResults` limit. `false` means more exist than the cap allowed
— raise `maxResults` or paginate. `supertypes` lists the immediate
supertypes of each implementation, which is what you want when an
inheritance chain runs through intermediate abstract classes or
mixins.

## Next steps

You can identify symbols and browse the declaration landscape. Now
trace how they're used and change them safely.

- [Trace usage](trace-usage.md) — every reference, with exhaustiveness
  proof, plus call hierarchies
- [Refactor safely](refactor-safely.md) — plan and apply renames with
  hash-based conflict detection
