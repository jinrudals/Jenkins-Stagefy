## Purpose

This document describes how to set up a GitLab System Hook to trigger a Jenkins job on all merge requests and execute Jenkins pipelines.

## Explanation

Modern CI/CD tools rely on a specific directory structure and expect a YAML file as the entry point. Owners or maintainers generally do not configure any other manual entry point beyond these YAML files.

However, with Jenkins, we must:

- Create a Jenkins job manually

- Write pipeline logic using Jenkins DSL

- Create a webhook endpoint

- Integrate it with GitLab or GitHub per project

This process is repetitive and cumbersome.

### The Simplified Approach

Thankfully, GitLab supports System Hooks—hooks that apply across all projects. System Hooks can be triggered on merge request events, meaning we don't have to configure Jenkins integration for each project individually.

The only challenge with this approach is that different projects might require different pipelines. This is where Stagefy helps: it allows each project to define its own pipeline via a common YAML format and entry stage.

#### Jenkins Job Example

```groovy
node() {
    // Checkout and locally merge source and target branches from environment variables
    checkout

    // If jenkins.yaml exists and contains an `MR` stage, execute it
    if (condition) {
        stagefy.run("jenkins.yaml", "MR")
    }

    // Update the GitLab commit status
    updatecommitstatus
}
```

#### Benefits

- **No per-project integration needed**: System Hook covers all repositories

- **True CI/CD behavior**: The pipeline is tested against the YAML file after merging

- **Branch-specific pipelines**: Projects can define different pipelines for different branches

This setup drastically reduces the overhead of Jenkins-GitLab integration and improves flexibility and consistency across projects.
