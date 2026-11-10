# 0002 — Custom charts with Compose Canvas

- **Status:** accepted
- **Date:** 2026-11-03

## Context

The dashboard needs a donut chart (today's time per app, with "Other") and bar charts (7 days and hourly breakdown). The specification (§2) allows either custom Canvas drawing or a FOSS library such as Vico.

## Decision

We draw the charts **with Compose Canvas**, without external libraries.

## Reasons

- Only two chart types are needed, and both are simple.
- Visual fidelity with Digital Wellbeing: the interactive donut (tap a slice to select the app) is easier to get right with our own code than by adapting a library.
- One less dependency to audit and maintain for F-Droid.

## Consequences

- We have to implement chart accessibility (TalkBack descriptions) and animations ourselves.
- If more complex charts are needed later, we reevaluate Vico.
