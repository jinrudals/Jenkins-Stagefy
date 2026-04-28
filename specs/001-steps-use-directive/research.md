# Research: `use` Step Directive

**Branch**: `001-steps-use-directive` | **Date**: 2026-04-28

## Decision 1: `use` 디렉티브 삽입 위치

**Decision**: `steps_run()`의 `content` 클로저 내부 `for` 루프에 `else if(each.containsKey("use"))` 분기 추가

**Rationale**: 기존 `sh`, `script`, `evaluate`, `setEnvFromFile` 분기와 동일한 패턴. `content` 클로저 내부에 있어야 `env`/`node` 래핑(lines 70-91)이 자동 적용되고 실행 순서가 보장됨.

**Alternatives considered**:
- `content` 클로저 외부에 별도 처리 → `env`/`node` 래핑 누락, 실행 순서 보장 불가. 기각.

---

## Decision 2: 자식 스테이지 생성 시 parent 전달 방식

**Decision**: `use` 처리 시 `this.getClass().newInstance(targetFile, targetStage, this)` 사용 (`this`를 parent로 전달)

**Rationale**: 전역 `construct_stage()`는 `parent = null`로 생성하므로 순환 참조 체인이 끊긴다. `this`를 parent로 전달해야 기존 `check_circular_loop()` 메커니즘이 `use` 체인까지 추적 가능.

기존 `construct_stage()` 시그니처:
```groovy
def construct_stage(String filename, String stagename) {
    return new Stagefy(filename, stagename, null)  // parent = null
}
```
`use` 처리:
```groovy
def childStage = this.getClass().newInstance(targetFile, targetStage, this)  // parent = this
```

**Alternatives considered**:
- `construct_stage()` 오버로드로 parent 받기 → 전역 함수 변경 필요, 다른 호출부 영향. 기각.

---

## Decision 3: 순환 참조 감지 — steps 타입 처리

**Decision**: `use` 디렉티브 실행 직전, `childStage.check_circular_loop(childStage)` 명시적 호출

**Rationale**: 기존 `run()`은 `stages`/`parallels` 타입에 대해서만 `check_circular_loop`를 호출하고, `steps` 타입은 건너뜀(line 201-204). `use`가 `steps` 타입을 참조하고 해당 스테이지 내에 다시 `use`가 있으면 무한 재귀 발생 가능. `use` 처리 시점에 항상 명시적으로 체크해야 함.

```groovy
// use 처리 시 항상 순환 참조 체크
childStage.check_circular_loop(childStage)
```

**Alternatives considered**:
- `run()`의 `steps` 분기에도 `check_circular_loop` 추가 → `use` 없는 일반 steps에도 불필요한 체크 추가. 범위 초과. 기각.

---

## Decision 4: `makeStage: true` (기본) 실행 방식

**Decision**: `this.script.stage(targetStage) { childStage.run() }`

**Rationale**: `stage()` 블록을 `this.script`로 호출하면 Jenkins UI에 스테이지가 생성됨. `childStage.run()` 내부에서 `when` 조건 평가, 타입 분기, 재귀 실행이 모두 처리됨.

---

## Decision 5: `makeStage: false` 실행 방식

**Decision**: `childStage.run()` 직접 호출 (stage 래핑 없음)

**Rationale**: `run()` 내부에서 타입에 따라 `steps_run()`/`parallels_run()`/`stages_run()`이 호출되므로, 래핑 없이 직접 호출해도 모든 타입이 올바르게 동작함. `parallels_run()`은 내부에서 `this.script.parallel(data)`를 호출하므로, 현재 stage 컨텍스트 안에서 병렬 실행이 가능함(Jenkins Scripted Pipeline 지원).

---

## Decision 6: 파일 경로 해석

**Decision**: 경로 변환 없이 `targetFile`을 그대로 `load_data(targetFile, targetStage)` 전달

**Rationale**: `load_data()`는 `readYaml(file: filename)`을 호출하며, Jenkins의 `readYaml`은 경로를 workspace root 기준 상대 경로로 해석함. 별도 path join 로직 불필요. 기존 `Jenkins.yml` 참조 방식과 동일.

---

## Decision 7: `use` 문법 파싱

**Decision**: `useValue.split(" from ", 2)` 로 `[stageName, filepath]` 추출

**Rationale**: 기존 `parallels_run()`의 `"StageName from file.yaml"` 파싱 방식(`each.split("from")`)과 동일한 패턴. 단, `split(" from ", 2)`를 사용해 2-token 분리를 보장하고, 스테이지명에 `from`이 포함된 경우 오파싱 방지.

현재 `parallels_run()` 파싱 (참조):
```groovy
def splitted = each.split("from")
nextStage = splitted[0].trim()
nextFile = splitted[1].trim()
```

`use` 디렉티브 파싱:
```groovy
def parts = useValue.split(" from ", 2)
def targetStage = parts[0].trim()
def targetFile = parts[1].trim()
```

---

## Decision 8: 오류 메시지

**Decision**: 파일 미존재/스테이지 미존재 시 `this.script.error()`로 빌드 실패 + 원인 명시

**Rationale**: 기존 오류 처리 방식(`this.script.error('No matching type')`, line 207)과 일관성 유지.

```groovy
// 파싱 실패 (from 없음)
this.script.error("use directive must follow 'StageName from filepath' format: '${useValue}'")

// 파일 미존재: readYaml이 자동으로 오류 발생 (Jenkins 내장)
// 스테이지 미존재: load_data()가 null 반환 → run()에서 처리
```
