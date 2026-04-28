# Data Model: OOP Refactoring of Stagefy

**Branch**: `003-oop-refactoring` | **Date**: 2026-04-28

## Entity Hierarchy

### Stage Layer

```
Stage (abstract)
├── StepsStage        — executes a list of steps within one Jenkins stage
├── SequentialStage   — orchestrates child stages sequentially
└── ParallelStage     — orchestrates child stages in parallel
```

#### Stage (abstract base)

| Field | Type | Description |
|-------|------|-------------|
| filename | String | Source YAML file path |
| stagename | String | Stage identifier in YAML |
| parent | Stage | Parent stage (null for root) |
| flag | boolean | Execution enabled state (from `when:` + parent propagation) |
| jenkins | JenkinsContext | Jenkins DSL adapter |

| Method | Returns | Description |
|--------|---------|-------------|
| run() | void | Load data, evaluate `when:`, dispatch to subclass |
| load() | Map | Load stage data from YAML via `jenkins.readYaml()` |
| checkCircularLoop(Stage other) | void | Walk parent chain; error if cycle detected |

**State transitions**: `created` → `loaded` (data read) → `enabled/disabled` (when evaluated) → `running` → `completed`

#### StepsStage

Inherits all Stage fields. No additional fields.

| Method | Returns | Description |
|--------|---------|-------------|
| stepsRun() | void | Resolve template/iterated steps, execute each step, wrap with env/node |

**Step resolution order**: For each entry in `data.steps`:
1. `template:` → resolve via TemplateResolver, inline resolved steps
2. `iterated:` → expand via IterationResolver, inline N×resolved steps
3. plain step → pass through as-is

#### SequentialStage

Inherits all Stage fields. No additional fields.

| Method | Returns | Description |
|--------|---------|-------------|
| stagesRun() | void | Build execution plan from entries, execute sequentially |

**Entry types**: String reference, `{template: ...}` map, `{iterated: ...}` map

#### ParallelStage

Inherits all Stage fields. No additional fields.

| Method | Returns | Description |
|--------|---------|-------------|
| parallelsRun() | void | Build closure map from entries, call `jenkins.parallel()` |

**Entry types**: Same as SequentialStage

---

### Step Layer

```
Step (abstract)
├── ShStep              — shell command execution
├── ScriptStep          — external Groovy script execution
├── SetEnvFromFileStep  — load env vars from YAML file
├── EvaluateStep        — evaluate Groovy expression
├── UseStageStep        — reference and execute another stage
├── TemplateStep        — inline a template's resolved steps
└── IteratedStep        — expand template N times with different bindings
```

#### Step (abstract base)

| Field | Type | Description |
|-------|------|-------------|
| jenkins | JenkinsContext | Jenkins DSL adapter |
| stage | Stage | Parent stage |
| raw | Map | YAML step definition |
| stageData | Map | Parent stage's full YAML data |

| Method | Returns | Description |
|--------|---------|-------------|
| run() | void | Execute the step (abstract) |

#### ShStep

| Behavior | Description |
|----------|-------------|
| run() | Substitute `${env.VAR}` patterns, prepend module prefix, call `jenkins.sh()` |

#### ScriptStep

| Behavior | Description |
|----------|-------------|
| run() | Call `jenkins.load(path).main()` |

#### SetEnvFromFileStep

| Behavior | Description |
|----------|-------------|
| run() | Read YAML file, set each `env` key via `jenkins.setEnvProperty()` |

#### EvaluateStep

| Behavior | Description |
|----------|-------------|
| run() | Call `jenkins.evaluate(expression)` |

#### UseStageStep

| Behavior | Description |
|----------|-------------|
| run() | Parse `"StageName from filepath"`, construct child stage, check circular, execute (with or without stage wrapper based on `makeStage`) |

#### TemplateStep

| Behavior | Description |
|----------|-------------|
| run() | Load template, validate, build bindings from step map, resolve steps, execute inline |

#### IteratedStep

| Behavior | Description |
|----------|-------------|
| run() | Load template, validate, expand `over:` to item list, for each item: build bindings, resolve steps, execute inline |

---

### Template System

#### Template

| Field | Type | Description |
|-------|------|-------------|
| name | String | Template stage name in YAML |
| filename | String | Source YAML file |
| data | Map | Full YAML data for this template |
| arguments | List\<String\> | Declared argument names |
| steps | List\<Map\> | Raw step definitions |

| Method | Returns | Description |
|--------|---------|-------------|
| validate() | void | Check structure: has arguments, has steps, no stages/parallels |
| validateBindings(Map bindings) | void | Check all declared arguments are supplied |

#### TemplateResolver

Stateless utility (`@NonCPS`).

| Method | Returns | Description |
|--------|---------|-------------|
| substituteBindings(String text, Map bindings) | String | Replace `{ARG}` tokens recursively (max depth 5) |
| resolveStepsWithBindings(List steps, Map bindings) | List\<Map\> | Apply substituteBindings to all string values in step maps |

**Recursive substitution**: Loop up to 5 passes. Each pass replaces all `{KEY}` tokens where KEY exists in bindings. Stop when no replacements made or depth limit reached. Error if limit reached with remaining resolvable tokens (circular reference).

#### IterationResolver

Stateless utility.

| Method | Returns | Description |
|--------|---------|-------------|
| expandOver(def over, JenkinsContext jenkins) | List | Resolve `over:` to list of items (strings or maps) |
| buildBindingsFromItem(String templateName, List args, def item) | Map | Build bindings map from a single item |

**over: resolution**:
- `List` → return as-is
- `String "env.VAR"` → read env var → try JSON parse → fallback comma-split
- else → error

---

### Jenkins Adapter

#### JenkinsContext

| Field | Type | Description |
|-------|------|-------------|
| script | Object | Jenkins pipeline script context (`this` from vars/) |

| Method | Wraps | Description |
|--------|-------|-------------|
| stage(name, body) | `script.stage()` | Create Jenkins stage |
| sh(cmd) | `script.sh()` | Execute shell command |
| parallel(map) | `script.parallel()` | Execute parallel branches |
| node(label, body) | `script.node()` | Bind to agent node |
| withEnv(list, body) | `script.withEnv()` | Set environment variables |
| readYaml(file) | `script.readYaml()` | Read YAML file |
| load(path) | `script.load()` | Load Groovy script |
| evaluate(expr) | `script.evaluation()` | Evaluate expression |
| error(msg) | `script.error()` | Throw pipeline error |
| getEnv(name) | `script.env[name]` | Read environment variable |
| setEnvProperty(k, v) | `script.env.setProperty()` | Set environment variable |
| println(msg) | `script.println()` | Console output (for DEBUG_LEVEL logging) |

---

### Factory Classes

#### StageFactory

Stateless. `@NonCPS` where possible.

| Method | Returns | Description |
|--------|---------|-------------|
| create(filename, stagename, parent, jenkins, data) | Stage | Dispatch based on YAML keys: `stages`→SequentialStage, `parallels`→ParallelStage, `steps`→StepsStage, `template`→error, else→error with valid keys |

#### StepFactory

Stateless. `@NonCPS` where possible.

| Method | Returns | Description |
|--------|---------|-------------|
| create(step, jenkins, stage, stageData) | Step | Dispatch based on step key: `sh`→ShStep, `script`→ScriptStep, etc., else→error with valid keys |

---

### Utility Classes

#### StageNameBuilder

Stateless. `@NonCPS`.

| Method | Returns | Description |
|--------|---------|-------------|
| forScalar(templateName, value) | String | `"${templateName}_${value}"` sanitized |
| forMap(templateName, entry, index) | String | Uses `_name` if present, else index |
| forTemplateRef(templateName, bindings, seenNames) | String | Unique name with conflict resolution |
| sanitize(name) | String | Replace invalid Jenkins stage name chars with `_` |

#### EnvResolver

Stateless. `@NonCPS`.

| Method | Returns | Description |
|--------|---------|-------------|
| resolve(text, envMap) | String | Replace `${env.VAR}` patterns with values |

#### ModuleResolver

Stateless. `@NonCPS`.

| Method | Returns | Description |
|--------|---------|-------------|
| buildPrefix(modules) | String | Build `module load ...` prefix string, or empty |

## Relationships

```
run() entry point
  └─ StageFactory.create()
       ├─ StepsStage
       │    ├─ StepFactory.create() per step
       │    │    ├─ ShStep → EnvResolver, ModuleResolver
       │    │    ├─ TemplateStep → Template, TemplateResolver, IterationResolver
       │    │    ├─ IteratedStep → Template, TemplateResolver, IterationResolver
       │    │    └─ UseStageStep → StageFactory (recursive)
       │    └─ env/node wrapping
       ├─ SequentialStage
       │    ├─ String refs → StageFactory (recursive)
       │    ├─ template: → Template, TemplateResolver
       │    └─ iterated: → Template, IterationResolver, TemplateResolver
       └─ ParallelStage
            └─ (same as SequentialStage, via parallel())
```

All classes interact with `JenkinsContext` for DSL calls. No class calls Jenkins DSL directly.
