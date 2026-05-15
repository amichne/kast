# Kast Overview

This page summarizes the raw note [[Kast-Overview]]. It is the highest-level
entry point in the source corpus and establishes the product frame for the rest
of the wiki.

## Source

This source is a project overview note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Kast-Overview]]

## Summary

This note presents Kast as a Kotlin semantic analysis tool built around a
lightweight client and a long-lived daemon. Its main contribution is the framing
that Kast exists to keep heavy analysis state resident so later queries and
refactors can remain fast.

It also sketches the major modules and the request flow between them, which
makes it the shortest path to understanding how the CLI, transport, and backend
fit together.

## Key claims

- Kast's core value proposition is a reusable client-daemon architecture.
- The system is organized into distinct modules with clear roles.
- Users and agents both enter through the same request flow.

## Connections

This source connects directly to the compiled pages below.

- Reinforces [[concepts/client-daemon-architecture]]
- Adds orientation to [[overview]]
- Adds context to [[entities/kast-cli]] and [[entities/backend-standalone]]

## Open questions

This source leaves several details to other notes.

- What are the concrete operational steps after installation?
- Which subsystems dominate correctness and performance risk?

## Pages updated from this source

The pages below were created or shaped by this source.

- [[overview]]
- [[concepts/client-daemon-architecture]]
- [[analyses/end-to-end-request-lifecycle]]
