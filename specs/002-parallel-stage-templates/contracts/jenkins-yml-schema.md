# Jenkins.yml Schema Contract: Stage Templates with Iteration

## Overview

This document defines the extended Jenkins.yml schema introduced by the stage templates feature. All existing constructs remain valid and unchanged.

---

## Template Stage Declaration

A template stage is a top-level key in Jenkins.yml with a `template:` sub-key.

```yaml
{template_name}:
  template:
    arguments:
      - {ARG_NAME_1}
      - {ARG_NAME_2}
  steps:
    - sh: some command with {ARG_NAME_1} and {ARG_NAME_2}
    - sh: another step using {ARG_NAME_1}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `template` | map | yes | Marks this stage as a template |
| `template.arguments` | list of strings | yes | Declared argument names; all must be supplied at instantiation |
| `steps` | list of step maps | yes | Steps to execute; `{ARG_NAME}` placeholders are substituted at instantiation |

**Prohibited inside a template**:
- `stages:` key
- `parallels:` key
- Step entries using the `template:` or `iterated:` keys

---

## Template Reference in `parallels`, `stages`, or `steps`

Instantiates a template once with a specific set of argument bindings.

```yaml
{parent_stage}:
  parallels:          # or: stages, steps
    - template: {template_name}
      {ARG_NAME_1}: {value1}
      {ARG_NAME_2}: {value2}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `template` | string | yes | Name of a template declared in the same Jenkins.yml |
| `{ARG_NAME}` | string | yes (per declared arg) | Binding value for each declared argument |
| Extra keys | any | no | Ignored silently |

**Behavior by parent context**:
- `parallels` → generates one parallel stage named `{template_name}` (or disambiguated if name conflicts)
- `stages` → generates one sequential stage named `{template_name}`
- `steps` → inlines the resolved steps; does NOT create a new stage

---

## Iterated Block in `parallels`, `stages`, or `steps`

Generates multiple instantiations by looping over a collection.

### Scalar list (single-argument templates only)

```yaml
{parent_stage}:
  parallels:          # or: stages, steps
    - iterated:
        template: {template_name}
        over:
          - {value1}
          - {value2}
```

### Map list (any number of arguments)

```yaml
{parent_stage}:
  parallels:          # or: stages, steps
    - iterated:
        template: {template_name}
        over:
          - {ARG1}: {value1}
            {ARG2}: {value2}
            _name: {stage_name_suffix}   # optional
          - {ARG1}: {value3}
            {ARG2}: {value4}
```

### Environment variable (scalar or map list via auto-detection)

```yaml
{parent_stage}:
  parallels:          # or: stages, steps
    - iterated:
        template: {template_name}
        over: env.{VAR_NAME}
```

The env var value is auto-detected at runtime:

| Env var value example | Resolved format | Template arity |
|-----------------------|-----------------|----------------|
| `arm64,x86_64` | Scalar list (comma-split) | Single-arg only |
| `["arm64","x86_64"]` | Scalar list (JSON) | Single-arg only |
| `[{"TARGET":"arm64","MODE":"debug"},{"TARGET":"x86_64","MODE":"release"}]` | Map list (JSON) | Multi-arg |

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `iterated` | map | yes | Container key for the iterated block |
| `iterated.template` | string | yes | Template name |
| `iterated.over` | list of scalars, list of maps, or `env.VAR_NAME` string | yes | Source collection |
| `_name` | string (in map entry) | no | Suffix for the generated stage name; falls back to 0-based index |

**Constraints**:
- Scalar-resolved `over:` (inline scalar list, JSON string array, or comma-split env string) requires exactly one declared argument
- Map-resolved `over:` (inline map list or JSON object array from env) must supply all declared arguments per entry; extra keys (other than `_name`) are ignored
- Empty collection or unset/empty env var → zero items; not an error

---

## Generated Stage Names

| Scenario | Stage name |
|---------|-----------|
| Direct `template:` ref in `parallels`/`stages` | `{template_name}` |
| Iterated, scalar over `[arm64, x86_64]` | `{template_name}_arm64`, `{template_name}_x86_64` |
| Iterated, map over with `_name: debug` | `{template_name}_debug` |
| Iterated, map over without `_name:` | `{template_name}_0`, `{template_name}_1`, ... |
| Iterated in `steps` context | No stage created; no name generated |

---

## Error Conditions

All errors below are raised at validation time (before any stage executes):

| Condition | Error |
|-----------|-------|
| `template:` references a non-existent stage | `Template '{name}' not found` |
| Referenced stage exists but has no `template:` key | `Stage '{name}' is not a template` |
| Required argument not supplied | `Template '{name}' requires argument '{arg}'` |
| Scalar `over:` used with multi-argument template | `Scalar iteration requires single-argument template; '{name}' declares {N} arguments` |
| `env.VAR_NAME` used with multi-argument template | same as above |
| Template definition contains `stages:` or `parallels:` | `Template '{name}' must contain only steps` |
| Two iterated items produce the same stage name | `Duplicate stage name '{name}'` |

---

## Backward Compatibility

All existing Jenkins.yml constructs are unaffected:
- String entries in `parallels`/`stages` (`"stageName"`, `"stageName from filepath"`) work as before
- Existing step types (`sh`, `script`, `use`, `setEnvFromFile`, `evaluate`) work as before
- Stages without a `template:` key behave identically to the current implementation
