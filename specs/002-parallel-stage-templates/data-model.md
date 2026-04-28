# Data Model: Stage Templates with Iteration

## Entities

### Template

A top-level stage definition in Jenkins.yml that declares reusable steps with variable placeholders.

**YAML structure**:
```yaml
{template_name}:
  template:
    arguments: [{ARG1}, {ARG2}, ...]   # required; list of argument names
  steps:
    - {step_type}: {step_value}        # one or more steps; {ARG_NAME} placeholders allowed
```

**Constraints**:
- `template.arguments` must be a non-empty list of strings
- `steps` must be present and non-empty
- Must NOT contain `stages:`, `parallels:`, or nested `template:` references
- Placeholder syntax inside step values: `{ARG_NAME}` (single braces, no `$`)
- Cannot be executed directly via `stagefy.run()` or `use` directive

**Runtime representation** (Groovy map from `readYaml`):
```
[
  template: [arguments: ["TARGET", "MODE"]],
  steps: [[sh: "compile --target {TARGET} --mode {MODE}"]]
]
```

---

### VariableBinding

A key-value pair supplied at template instantiation time that substitutes a named placeholder.

**Source**: Sibling keys alongside `template:` in a reference entry, or a map entry in an `over:` list.

**Validation rules**:
- All arguments declared in `Template.arguments` must be present in the binding map
- Extra keys not in `Template.arguments` are ignored
- Values are always treated as strings during substitution

---

### TemplateReference

A single instantiation of a template, defined as a map entry in a `parallels`, `stages`, or `steps` list.

**YAML structure**:
```yaml
- template: {template_name}
  {ARG1}: {value1}
  {ARG2}: {value2}
```

**Behavior by context**:
| Context | Result |
|---------|--------|
| `parallels` | Generates one new parallel stage; stage name = `{template_name}` (or `{template_name}_{value}` if name collides) |
| `stages` | Generates one new sequential stage |
| `steps` | Inlines the resolved steps into the current stage; no new stage created |

---

### IteratedBlock

A loop construct that generates multiple template instantiations from a collection.

**YAML structure** (scalar list):
```yaml
- iterated:
    template: {template_name}     # required; must reference a single-argument template
    over: [{val1}, {val2}, ...]   # inline YAML list of scalar values
```

**YAML structure** (map list):
```yaml
- iterated:
    template: {template_name}
    over:
      - {ARG1}: {val1}
        {ARG2}: {val2}
        _name: {stage_name_suffix}   # optional; used for stage name generation
      - {ARG1}: {val3}
        {ARG2}: {val4}
```

**YAML structure** (env variable):
```yaml
- iterated:
    template: {template_name}
    over: env.{VAR_NAME}   # resolved at runtime; auto-detected as scalar list or map list
```

**Behavior by context**:
| Context | Result |
|---------|--------|
| `parallels` | Generates N parallel stages |
| `stages` | Generates N sequential stages, in collection order |
| `steps` | Inlines N copies of resolved template steps, in collection order; no new stages |

**Stage name generation** (for `parallels`/`stages` context):
| `over:` type | Stage name |
|-------------|-----------|
| Scalar list (inline or env-resolved) | `{template_name}_{value}` |
| Map list with `_name:` key (inline or env-resolved) | `{template_name}_{_name}` |
| Map list without `_name:` key (inline or env-resolved) | `{template_name}_{index}` (0-based) |

**`over: env.VAR_NAME` auto-detection** (resolved at runtime):
| Env var value | Resolved as | Template arity |
|---------------|-------------|----------------|
| `arm64,x86_64` | Scalar list (comma-split) | Single-arg only |
| `["arm64","x86_64"]` | Scalar list (JSON array of strings) | Single-arg only |
| `[{"T":"arm64","M":"debug"},...]` | Map list (JSON array of maps) | Multi-arg |
| empty / unset | Empty list | Zero iterations, no error |

**Validation rules**:
- `template:` must reference an existing template in the same YAML file
- Scalar collection (inline or env-resolved) requires the template to declare exactly one argument
- Map collection (inline or env-resolved) entries must supply all declared arguments
- Empty `over:` → zero items; not an error
- `over: env.VAR_NAME` with unresolvable variable → error at point of resolution

---

### EnvVarReference

Represents a runtime-resolved environment variable used as the `over:` source.

**Syntax**: `env.{VAR_NAME}` (string value of `over:` field)

**Resolution**:
1. Parse `env.{VAR_NAME}` to extract `VAR_NAME`
2. At runtime, call `this.script.env[VAR_NAME]` — returns a string or null
3. If null or empty: yield zero items (not an error)
4. If non-null: auto-detect format:
   - **Try JSON parse** (`groovy.json.JsonSlurper`):
     - JSON array of maps → resolved as map list; multi-argument templates supported
     - JSON array of strings/primitives → resolved as scalar list; single-argument templates only
   - **JSON parse fails** → split by comma (`,`), strip whitespace → scalar list; single-argument templates only

**Note**: Jenkins env vars are always strings. The JSON auto-detection layer allows map-list semantics (multi-argument iteration) to be encoded in a single env var string. The comma-split fallback preserves the conventional `VAR=a,b,c` Jenkins parameter pattern.

---

## State Transitions

```
YAML File
  │
  ├── Top-level stage with template: key
  │     └── [Validation] arguments present, steps present, no structural elements
  │               ↓
  │         Template (ready for instantiation)
  │
  ├── parallels/stages list entry: {template: X, ...}
  │         ↓
  │   TemplateReference → [Validate bindings] → Resolved Steps → New Stage
  │
  ├── parallels/stages/steps list entry: {iterated: {template: X, over: [...]}}
  │         ↓
  │   IteratedBlock → [Expand collection] → N × TemplateReference → N Results
  │
  └── steps list entry: {template: X, ...}
            ↓
      TemplateReference → [Validate bindings] → Inlined Steps (no new stage)
```

## Groovy Runtime Types

| YAML construct | Groovy type after `readYaml` |
|---------------|------------------------------|
| `arguments: [A, B]` | `java.util.ArrayList<String>` |
| `over: [x, y]` | `java.util.ArrayList<String>` (scalar) or `ArrayList<LinkedHashMap>` (maps) |
| `over: env.PLATFORMS` | `String` — must be detected by `startsWith("env.")` check |
| Template reference entry | `java.util.LinkedHashMap` with `template` key |
| Iterated entry | `java.util.LinkedHashMap` with `iterated` key |
| Existing string entry in parallels/stages | `java.lang.String` |
