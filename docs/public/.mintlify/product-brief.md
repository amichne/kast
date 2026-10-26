# Product brief

## Description

Kast gives agents facts from the Kotlin compiler.
Readers can see where a value goes and which code depends on a function.

## Primary audience

The main readers are Kotlin developers who already use a coding agent.
They want to understand what Kast adds.
The core docs assume the latest Kast release.

## Jobs to be done

1. Explore one example through code and a diagram.
2. Install Kast and connect an agent.
3. Ask a useful question about the reader's code.

## Motivation

A name or type alone does not prove how code connects.
Show a route and a changed example before the tool contracts.
Label each example so readers know whether Kast ran.

## Navigation and ownership

Keep three tabs: Guides, Architecture, and Reference.
Keep the main menus to two levels. Keep detailed schemas collapsed.
Keep useful page paths. Give each fact one source owner.
Edit generated contracts through their generator.

## Claims and limits

Kast supplies facts. The agent interprets them.
Do not imply that every language, library, or refactor has support.
An unfinished query does not prove that code is absent. A source link does not prove that code ran or a build passed.
Keep query output, retained results, and source references distinct.

## Visuals

Use the shared value explorer for the core example. Use Beautiful Mermaid to show how Kast works.
Keep source details behind the Evidence control.
Use text and shape alongside color to explain routes and missing evidence.
