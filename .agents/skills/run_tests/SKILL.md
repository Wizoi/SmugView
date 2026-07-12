---
name: run_tests
description: Runs local JVM unit tests, instrumented Android integration tests, or all tests combined.
---

# SmugView Test Runner (run_tests)

This skill allows the agent to execute unit and integration test suites on SmugView. When running tests, the agent MUST first call the `ask_question` tool to prompt the user to choose the target test run option. Follow the structured YAML instructions:

*   **[Test Runner Guide](file:///c:/src/kidzi/GitHub/SmugView/.agents/resources/run_tests.yaml):** Read this file using the `view_file` tool to retrieve the environment parameters and execution commands.
