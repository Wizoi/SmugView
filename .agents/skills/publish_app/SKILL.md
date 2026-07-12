---
name: publish_app
description: Updates the application version, signs the build, and publishes the release Android App Bundle (.aab) to Google Play Console using Gradle Play Publisher.
---

# SmugView Application Publisher (publish_app)

This skill orchestrates updating the version of SmugView, signing the release build, compiling the release Android App Bundle (.aab), and publishing it to the Google Play Console using Gradle Play Publisher.

When executing a publishing task, load and follow the structured YAML guide file:

*   **[Publishing Guide](file:///c:/src/kidzi/GitHub/SmugView/.agents/resources/publish_app.yaml):** Read this file using the `view_file` tool to retrieve environmental pre-requisites, version modification workflows, and build/publishing Gradle commands.
