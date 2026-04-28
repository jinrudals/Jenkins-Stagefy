# Implementation Plan: `use` Step Directive for External Stage Inclusion

**Branch**: `001-steps-use-directive` | **Date**: 2026-04-28 | **Spec**: [spec.md](spec.md)

## Summary

`steps` 블록 내에서 `use: StageName from filepath` 디렉티브를 통해 외부 YAML 파일의 스테이지를 직접 참조·실행할 수 있도록 한다. `makeStage` 옵션(기본 `true`)으로 Jenkins UI 스테이지 생성 여부를 제어한다. 변경은 `vars/stagefy.groovy`의 `Stagefy` 클래스 `steps_run()` 메서드에 국한되며, 기존 모든 문법과 하위 호환성을 유지한다.

## Technical Context

**Language/Version**: Groovy (Jenkins Shared Library, Pipeline DSL)
**Primary Dependencies**: Jenkins Pipeline: Utility Steps Plugin (`readYaml`), `org.jenkinsci.plugins.pipeline.modeldefinition.Utils`
**Storage**: N/A (파일 시스템 YAML, Jenkins workspace)
**Testing**: Jenkins 파이프라인 실제 실행으로 검증 (통합 테스트)
**Target Platform**: Jenkins CI server (Scripted Pipeline)
**Project Type**: Jenkins Shared Library
**Performance Goals**: N/A (파이프라인 오케스트레이션 레이어)
**Constraints**: Jenkins CPS (Continuation Passing Style) 실행 모델 준수. `@NonCPS` 어노테이션 기존 패턴 유지.
**Scale/Scope**: 단일 파일(`vars/stagefy.groovy`) 변경. 신규 파일 없음.

## Constitution Check

*Constitution 파일이 초기화되지 않아 프로젝트별 원칙 미정의. 일반 원칙 기준 평가:*

- **최소 변경**: 단일 메서드(`steps_run()`) 내 `else if` 분기 추가만으로 구현. ✅
- **하위 호환성**: 기존 `from` 문법, 기존 스텝 타입 모두 변경 없음. ✅
- **순환 참조 안전성**: 기존 `check_circular_loop` 메커니즘 재사용, 명시적 호출 추가. ✅

*게이트 위반 없음.*

## Project Structure

### Documentation (this feature)

```text
specs/001-steps-use-directive/
├── plan.md          ← 이 파일
├── spec.md          ← 기능 명세
├── research.md      ← 기술 결정 근거
├── data-model.md    ← 데이터 모델 및 상태 흐름
├── quickstart.md    ← 사용 예시
├── contracts/
│   └── yaml-schema.md   ← YAML 스키마 컨트랙트
├── checklists/
│   └── requirements.md
└── tasks.md         ← /speckit.tasks 실행 시 생성
```

### Source Code (변경 대상)

```text
vars/
└── stagefy.groovy      ← 유일한 변경 파일
    └── Stagefy 클래스
        └── steps_run()    ← use 분기 추가
```

## 핵심 구현 설계

### `steps_run()` 변경 내용

`content` 클로저 내부의 `for(each in data["steps"])` 루프에 `use` 분기를 추가한다.

```groovy
// 추가할 분기 (기존 evaluate 분기 다음)
} else if (each.containsKey("use")) {
    def useValue = each["use"]
    def makeStage = each.containsKey("makeStage") ? each["makeStage"] : true

    // "StageName from filepath" 파싱
    if (!useValue.contains(" from ")) {
        this.script.error("use directive must follow 'StageName from filepath' format: '${useValue}'")
    }
    def parts = useValue.split(" from ", 2)
    def targetStage = parts[0].trim()
    def targetFile = parts[1].trim()

    // this를 parent로 전달 → 순환 참조 체인 유지
    def childStage = this.getClass().newInstance(targetFile, targetStage, this)
    childStage.check_circular_loop(childStage)

    if (makeStage) {
        this.script.stage(targetStage) {
            childStage.run()
        }
    } else {
        childStage.run()
    }
}
```

### 설계 핵심 포인트

| 포인트 | 설명 |
|--------|------|
| `content` 클로저 내부 삽입 | `env`/`node` 래핑이 자동 적용됨 |
| `parent = this` 전달 | 순환 참조 체인이 `use` 깊이까지 추적 가능 |
| `check_circular_loop` 명시 호출 | `steps` 타입은 `run()`에서 자동 호출 안 됨 |
| `makeStage: true` | `stage(targetStage) { childStage.run() }` |
| `makeStage: false` | `childStage.run()` 직접 호출 |
| `parallels` + `makeStage: false` | `parallels_run()` → `parallel()` 호출 → Jenkins 지원 |
| 파일 경로 | `readYaml(file: targetFile)` — workspace root 기준 상대 경로 자동 처리 |

### 순환 참조 감지 흐름

```
A (steps) → use B → use A   ← 순환
  childB = new Stagefy("f", "B", stageA)
  childB.check_circular_loop(childB):
    childB.parent = stageA
    stageA.filename == "f" && stageA.stagename == "A" != "B"
    stageA.parent = null → return true (OK at this level)
  
  childB.run() → steps_run() → use A 발견
    childA2 = new Stagefy("f", "A", stageB)
    childA2.check_circular_loop(childA2):
      childA2.parent = stageB
      stageB.stagename == "B" != "A"
      stageB.parent = stageA
      stageA.stagename == "A" == childA2.stagename → THROW ✅
```

## Complexity Tracking

*Constitution 위반 없음. 이 섹션은 해당 없음.*
