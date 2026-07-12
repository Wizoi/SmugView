---
name: deploy_app
description: Builds the application if changes exist, then deploys it to the Android Emulator and/or compiles the release App Bundle (.aab).
---

# SmugView Application Deployer (deploy_app)

This skill allows the agent to install SmugView to the emulator and/or build the release App Bundle (.aab). When executing these actions, load and follow the structured YAML instructions:

*   **[Deployment Guide](file:///c:/src/kidzi/GitHub/SmugView/.agents/resources/deploy_app.yaml):** Read this file using the `view_file` tool to retrieve the target build commands (debug install or release bundle compiler).
