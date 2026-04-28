# Contract: Stagefy Public API

**Branch**: `003-oop-refactoring` | **Date**: 2026-04-28

## Public API (vars/stagefy.groovy global functions)

These functions are the external contract. They MUST NOT change signature or behavior.

### `run(filename, stagename)`

Entry point for pipeline execution. Creates root stage and executes within a Jenkins `stage()` block.

```groovy
// Signature (unchanged)
def run(filename, stagename)

// Usage in Jenkinsfile
stagefy.run("Jenkins.yml", "MR")
```

**Behavior**: Reads YAML, creates Stage hierarchy, executes. Identical pre/post refactoring.

### `construct_stage(filename, stagename)`

Creates a stage object for programmatic use.

```groovy
// Signature (unchanged)
def construct_stage(String filename, String stagename)

// Returns: Stage object (was Stagefy, now Stage subclass — duck-typed compatible)
```

### `load_data(filename, stagename)`

Reads YAML and returns stage data map.

```groovy
// Signature (unchanged)
def load_data(String filename, String stagename)

// Returns: Map (YAML data for the named stage)
```

### `evaluation(value)`

Evaluates a Groovy expression string.

```groovy
// Signature (unchanged)
def evaluation(value)

// Returns: result of evaluate(value)
```

### `setEnvFromFile(filename)`

Loads environment variables from a YAML file.

```groovy
// Signature (unchanged)
def setEnvFromFile(filename)
```

## YAML Configuration Contract

The YAML schema is unchanged. All existing YAML files continue to work without modification.

### Stage types (determined by top-level key)

| Key | Stage Type | Description |
|-----|-----------|-------------|
| `steps:` | StepsStage | List of step directives |
| `stages:` | SequentialStage | List of stage references |
| `parallels:` | ParallelStage | List of parallel branch references |
| `template:` | Template (not directly executable) | Reusable step definition |

### Step directives (within `steps:` list)

| Key | Step Type | Format |
|-----|----------|--------|
| `sh:` | ShStep | `sh: "command string"` |
| `script:` | ScriptStep | `script: "path/to/script.groovy"` |
| `setEnvFromFile:` | SetEnvFromFileStep | `setEnvFromFile: "path/to/env.yaml"` |
| `evaluate:` | EvaluateStep | `evaluate: "groovy expression"` |
| `use:` | UseStageStep | `use: "StageName from filepath"` |
| `template:` | TemplateStep | `template: "TemplateName"` + arg keys |
| `iterated:` | IteratedStep | `iterated: {template: ..., over: ...}` |

### Optional stage modifiers

| Key | Type | Description |
|-----|------|-------------|
| `when:` | String | Groovy expression; stage skipped if false |
| `env:` | Map | Environment variables via `withEnv()` |
| `node:` | String | Jenkins agent label via `node()` |
| `modules:` | List | Module load commands prepended to shell steps |

## Backward Compatibility Guarantee

- All existing `Stagefy` class methods are preserved as methods on the new `Stage` hierarchy
- `construct_stage()` returns an object with the same `run()` method
- `check_circular_loop()` behavior is identical
- YAML parsing and stage dispatch logic produces identical execution order
- Error messages may be improved but all existing error conditions still trigger errors
