---
name: run-tests
description: Runs local JVM unit tests, instrumented Android integration tests, or all tests combined.
---

# SmugView Test Runner (run-tests)

This skill allows the agent to execute unit and integration test suites on SmugView. When running tests, the agent MUST first ask the user (AskUserQuestion) to choose the target test run option. Follow the structured YAML instructions:

*   **[Test Runner Guide](.agents/resources/run_tests.yaml):** Read this file using the Read tool to retrieve the environment parameters and execution commands.
