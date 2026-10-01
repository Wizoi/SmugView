---
name: deploy-app
disable-model-invocation: true
description: Builds the application if changes exist, then deploys it to the Android Emulator and/or compiles the release App Bundle (.aab).
---

# SmugView Application Deployer (deploy-app)

This skill allows the agent to install SmugView to the emulator and/or build the release App Bundle (.aab). When executing these actions, load and follow the structured YAML instructions:

*   **[Deployment Guide](.agents/resources/deploy_app.yaml):** Read this file using the Read tool to retrieve the target build commands (debug install or release bundle compiler).
