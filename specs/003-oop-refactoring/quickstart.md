# Quickstart: OOP Refactoring of Stagefy

**Branch**: `003-oop-refactoring` | **Date**: 2026-04-28

## What Changes for Users?

**Nothing.** The refactoring is entirely internal. All existing Jenkinsfiles and YAML configurations continue to work without modification.

## What Changes Internally?

The monolithic `Stagefy` class is split into a hierarchy of focused classes:

### Before (monolithic)
```
Stagefy class (~400 lines)
  ├── run()           — dispatch + when + circular check
  ├── steps_run()     — step execution + template + iteration
  ├── parallels_run() — parallel execution + template + iteration
  ├── stages_run()    — sequential execution + template + iteration
  ├── substituteBindings(), resolveStepsWithBindings(), ...
  ├── validateTemplateData(), validateBindings(), ...
  ├── expandIteratedOver(), buildBindingsFromItem(), ...
  ├── buildStageNameFor*(), sanitizeStageName(), ...
  └── executeStep()   — step type dispatch
```

### After (OOP hierarchy)
```
JenkinsContext          — adapter for all Jenkins DSL calls
Stage (abstract)        — base: load, when, circular check
  ├── StepsStage        — step execution with env/node wrapping
  ├── SequentialStage   — sequential stage orchestration
  └── ParallelStage     — parallel stage orchestration
Step (abstract)         — base for step execution
  ├── ShStep, ScriptStep, EvaluateStep, SetEnvFromFileStep
  ├── UseStageStep, TemplateStep, IteratedStep
Template                — template validation and data
TemplateResolver        — {ARG} substitution (recursive, depth 5)
IterationResolver       — over: expansion (list/env/JSON/CSV)
StageFactory            — YAML → Stage subclass
StepFactory             — step map → Step subclass
StageNameBuilder        — unique stage name generation
EnvResolver             — ${env.VAR} substitution
ModuleResolver          — module load prefix building
```

## New Feature: DEBUG_LEVEL

Set the `DEBUG_LEVEL` environment variable to control execution tracing:

| Level | Output |
|-------|--------|
| 0 (default) | Silent — no extra logging |
| 1 | Stage entry/exit messages |
| 2 | Step-level execution detail |

```groovy
// In Jenkinsfile
withEnv(["DEBUG_LEVEL=2"]) {
    stagefy.run("Jenkins.yml", "MR")
}
```

## New Behavior: Fail-Fast on Unknown Directives

If a YAML step or stage contains an unrecognized key, Stagefy now immediately fails with a clear error message listing valid options, instead of silently ignoring it.

## How to Extend

### Adding a new Step type

1. Create a new class extending `Step` in `vars/stagefy.groovy`
2. Implement `run()` method
3. Add the new key to `StepFactory.create()` dispatch

### Adding a new Stage type

1. Create a new class extending `Stage` in `vars/stagefy.groovy`
2. Implement the execution method
3. Add the new key to `StageFactory.create()` dispatch

## Testing

Existing test files verify backward compatibility:
- `examples/test_use_inline.yaml`
- `examples/test_use_dag.yaml`
- `examples/test_use_circular.yaml`
- `examples/test_use_makestage.yaml`
- `examples/Jenkinsfile.test_templates`

All must produce identical results after refactoring.
