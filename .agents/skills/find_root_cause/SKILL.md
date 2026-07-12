---
name: find_root_cause
description: Performs a directed, evidence-based root cause analysis for any bugs, errors, or unexpected behaviors in the SmugView codebase.
---

# Directed Root Cause Analysis (find_root_cause)

This skill triggers the SmugView Directed Root Cause Analysis (RCA) workflow. When analyzing any bugs, compilation errors, or runtime anomalies, the agent must load and follow the structured instructions and steps defined in the YAML guide file:

*   **[RCA Guidelines File](file:///c:/src/kidzi/GitHub/SmugView/.agents/resources/find_root_cause.yaml):** Read this file using the `view_file` tool to retrieve the pre-analysis checks, persona methodologies, and reporting framework.
