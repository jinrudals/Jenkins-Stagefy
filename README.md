# Jenkins Stagefy

## Introduction
Modern CI/CD tools like GitHub Actions and GitLab CI/CD describe pipelines using YAML files.
This approach makes it easy for engineers—regardless of their familiarity with the CI/CD tool’s internals—to write and share pipelines across projects.

However, Jenkins requires pipelines to be written in Jenkins DSL, which introduces several inconveniences:

Engineers often avoid writing pipelines themselves; instead, the Jenkins administrator writes them, even though the DSL is relatively simple.

Jenkins pipelines (especially stages) are not easily reusable across projects.

To address these issues, I developed a simple Groovy-based solution that allows Jenkins stages to be defined in YAML format and invoked within Jenkins pipeline jobs. This makes pipelines more modular, reusable, and easier for all team members to contribute to.

---

## YAML structure
```yaml
build:
  stages:
    - compile
    - test
    - package

test:
  parallels:
    - unitTest
    - integrationTest

deploy:
  steps:
    - sh: ./scripts/deploy.sh
    - script: groovy/deploy.groovy
    - evaluate: |
        echo "Custom DSL"
        retry(3) {
            sh "curl http://example.com"
        }
```
### Stage Types
There are three types of stages supported:

- Sequential Stage (stages)
    
    A structural stage that aggregates other stages and runs them in sequence.

- Parallel Stage (parallels)

    A structural stage that aggregates other stages and runs them in parallel.

- Single Stage (steps)

    An executable stage that includes one or more steps, such as:

    - sh: a shell command

    - script: a reference to an external Groovy script file

    - evaluate: an inline Jenkins Pipeline DSL block

## Stage Reusability
```yaml
stageName:
    stages:
        - stageA        # stage that gets other.yaml
        - stageB from other.yaml
```
You can also import stages from other YAML files using the from keyword.
This enables cross-project stage sharing, improving reusability and consistency across pipelines.

> **Planned Feature**:
>
> Support for loading stages directly from a remote URL, without requiring prior local definition.

---
## How to
```groovy
// load library

node() {
    library.run("jenkins.yaml", "start-point")
}
```
OR
```groovy
// load library
pipeline {
    agent any

    stages{
        stage("init") {
            steps {
                script{
                    library.run("jenkins.yaml", "start")
                }
            }
        }
    }
}
```

This library constructs the details of each stage at runtime, which allows dynamic behavior.

For example, a previous stage might generate a new YAML file that subsequent stages can load and execute.
