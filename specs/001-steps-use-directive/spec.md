# Feature Specification: `use` Step Directive for External Stage Inclusion

**Feature Branch**: `001-steps-use-directive`
**Created**: 2026-04-28
**Status**: Draft

## Overview

파이프라인 작성자가 외부 YAML 파일의 스테이지를 `steps` 블록 내에서 직접 참조하고 실행할 수 있도록 `use` 스텝 디렉티브를 추가한다.

현재 외부 YAML 파일의 스테이지를 참조하려면 (1) 파일을 다운로드하는 스테이지와 (2) 해당 파일을 참조하는 `stages`/`parallels` 항목, 두 개의 분리된 스테이지가 필요하다. 이는 논리적으로 하나의 흐름인 작업을 인위적으로 두 스테이지로 나누는 문제를 야기한다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 외부 스테이지를 steps 내에서 실행 (Priority: P1)

파이프라인 작성자가 외부 YAML 파일을 다운로드한 직후, 동일한 `steps` 블록 안에서 해당 파일의 특정 스테이지를 `use` 디렉티브로 즉시 실행할 수 있다. `makeStage`의 기본값은 `true`이므로, 별도의 옵션 없이 사용하면 Jenkins UI에 새 스테이지가 생성된다.

**Why this priority**: 이 기능이 없으면 동적으로 획득한 YAML 파일을 같은 스테이지 내에서 사용하는 것이 불가능하여, 불필요한 스테이지 분리가 강제된다. 이것이 이 기능의 핵심 요구사항이다.

**Independent Test**: Jenkins.yml 하나만으로, wget으로 다운로드한 external.yaml의 특정 스테이지가 Jenkins 빌드 뷰에 독립 스테이지로 표시되며 정상 실행되는지 확인할 수 있다.

**Acceptance Scenarios**:

1. **Given** `steps` 블록에 `sh: wget ... -o other.yaml`과 `use: X from other.yaml`이 정의되어 있을 때, **When** 파이프라인이 실행되면, **Then** wget이 먼저 실행되고, 이어서 `other.yaml`의 `X` 스테이지가 Jenkins UI에 새 스테이지로 생성되어 실행된다.
2. **Given** `use` 디렉티브에 `makeStage` 옵션이 없을 때, **When** 파이프라인이 실행되면, **Then** `makeStage: true`가 기본값으로 적용되어 Jenkins 스테이지가 생성된다.
3. **Given** `use: X from other.yaml`에서 `X` 스테이지가 `parallels` 또는 `stages` 타입일 때, **When** 실행되면, **Then** 해당 타입의 동작(병렬/순차)이 그대로 수행된다.

---

### User Story 2 - makeStage: false 로 인라인 실행 (Priority: P2)

파이프라인 작성자가 외부 스테이지의 내용을 현재 스테이지에 인라인으로 병합하여 실행하고 싶을 때 `makeStage: false`를 지정한다. Jenkins UI에 새 스테이지가 생성되지 않고, 현재 스테이지의 일부로 실행된다.

**Why this priority**: 공유 설정 스크립트처럼 논리적으로 현재 스테이지의 일부인 작업을 별도 스테이지로 분리하지 않아야 할 경우에 필요하다.

**Independent Test**: `makeStage: false`로 정의된 `use` 디렉티브가 있는 파이프라인에서, Jenkins 빌드 뷰에 추가 스테이지가 생기지 않고 현재 스테이지 안에서 외부 스테이지의 steps가 실행되는지 확인할 수 있다.

**Acceptance Scenarios**:

1. **Given** `use: X from other.yaml`에 `makeStage: false`가 설정되어 있을 때, **When** 파이프라인이 실행되면, **Then** Jenkins UI에 `X`라는 이름의 새 스테이지가 생성되지 않고, `X`의 steps 내용이 현재 스테이지 안에서 순서대로 실행된다.
2. **Given** `makeStage: false`인 `use` 디렉티브 앞뒤로 다른 `sh` steps가 있을 때, **When** 실행되면, **Then** 모든 steps가 정의 순서대로 하나의 스테이지 내에서 실행된다.

---

### Edge Cases

- 참조된 파일(`other.yaml`)이 `use` 스텝 실행 시점에 존재하지 않을 경우 명확한 오류 메시지와 함께 빌드가 실패해야 한다.
- 참조된 스테이지 이름(`X`)이 파일 내에 존재하지 않을 경우 명확한 오류 메시지와 함께 빌드가 실패해야 한다.
- `use` 디렉티브로 참조된 스테이지가 다시 `use` 디렉티브를 포함할 경우(중첩), 중첩 실행은 허용된다. 단, 순환 참조가 감지되면 즉시 빌드를 실패시키고 순환 경로를 명시한 오류 메시지를 출력한다. 순환 참조는 어떠한 경우에도 허용되지 않는다.
- `makeStage: false`인 경우, 참조된 스테이지가 `parallels` 타입이라도 허용된다. 병렬 실행은 현재 스테이지 컨텍스트 안에서 `parallel()` 호출로 수행되며, Jenkins는 이를 정상 지원한다.
- `when` 조건이 설정된 외부 스테이지를 `use`로 참조할 경우, 해당 `when` 조건이 정상 평가되어야 한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 파이프라인 작성자는 `steps` 블록 내에서 `use: StageName from filepath` 형태의 디렉티브를 사용할 수 있어야 한다. 여기서 `filepath`는 Jenkins workspace root(`$WORKSPACE`) 기준 상대 경로이며, 기존 `readYaml`, `load` 등의 Jenkins 관행과 동일하다.
- **FR-002**: `makeStage` 옵션의 기본값은 `true`이며, 명시하지 않으면 참조된 스테이지가 Jenkins UI에 새 스테이지로 생성된다.
- **FR-003**: `makeStage: false`로 지정하면 새 Jenkins 스테이지를 생성하지 않고, 참조된 스테이지의 내용을 현재 실행 컨텍스트에 인라인으로 병합하여 실행해야 한다.
- **FR-004**: `use` 디렉티브는 `steps` 블록 내 다른 스텝들(`sh`, `script`, `evaluate` 등)과 순서를 보장하며 순차 실행되어야 한다.
- **FR-005**: 참조된 파일 또는 스테이지가 존재하지 않을 경우, 파이프라인은 명확한 오류 메시지를 출력하고 실패해야 한다.
- **FR-006**: 기존 `stages`/`parallels`의 `from` 문법은 변경 없이 그대로 유지되어야 한다(하위 호환성 보장).
- **FR-007**: 중첩 `use` 디렉티브는 허용된다. 동일한 스테이지가 여러 경로에서 참조되는 DAG 구조(예: `A→B→C`, `A→C→D`)는 정상 허용된다. 단, 스테이지가 자신의 실행 조상 체인에 나타나는 경우(예: `A→B→A`, `A→B→B`)는 순환 참조로 간주하며, 어떠한 경우에도 허용되지 않는다. 감지 즉시 빌드를 실패시키고 순환 경로를 명시한 오류 메시지를 출력해야 한다.
- **FR-008**: 참조된 스테이지의 타입(`steps`, `stages`, `parallels`)에 상관없이 해당 타입의 실행 방식이 그대로 동작해야 한다. `makeStage: false` + `parallels` 타입 조합도 허용되며, 이 경우 현재 스테이지 컨텍스트 안에서 병렬 실행된다.

### Key Entities

- **use 디렉티브**: `steps` 블록 내 하나의 스텝 항목. `use` 키(스테이지 이름 + 파일 경로)와 `makeStage` 불리언 옵션으로 구성된다.
- **외부 YAML 파일**: `use` 디렉티브가 참조하는 파일. `steps` 실행 시점에 파일 시스템에 존재해야 한다.
- **참조 스테이지**: 외부 YAML 파일 내의 특정 스테이지 정의. 기존 스테이지 타입(`steps`, `stages`, `parallels`)을 그대로 따른다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 기존에 두 개의 스테이지로 나누어야 했던 "다운로드 후 외부 스테이지 실행" 패턴을 단일 스테이지 내 `steps`로 표현할 수 있다.
- **SC-002**: `makeStage: true`(기본값) 사용 시, Jenkins 빌드 뷰에 참조된 스테이지 이름이 정확히 표시된다.
- **SC-003**: `makeStage: false` 사용 시, Jenkins 빌드 뷰에 추가 스테이지가 생성되지 않는다.
- **SC-004**: `use` 디렉티브가 포함된 파이프라인에서 기존 `sh`, `script`, `evaluate` 등 다른 스텝 타입이 모두 정상 동작한다(회귀 없음).
- **SC-005**: 존재하지 않는 파일/스테이지 참조 시 빌드 실패 메시지가 문제의 원인(파일명, 스테이지명)을 명시한다.

## Assumptions

- `use` 디렉티브로 참조되는 파일은 해당 스텝이 실행되는 시점에 이미 파일 시스템에 존재한다고 가정한다(사전에 `sh: wget ...` 등으로 다운로드).
- `use` 문법은 `steps` 블록 내에서만 허용되며, `stages`/`parallels` 블록에서의 `from` 문법과 별개로 공존한다.
- `makeStage: false`인 경우, 참조된 스테이지의 `node`, `env` 래핑 옵션은 인라인 실행 시에도 적용된다.
- 이 기능은 기존 `stages`/`parallels`의 `StageName from file.yaml` 문법을 대체하지 않고 보완하는 것을 목표로 한다.

## Clarifications

### Session 2026-04-28

- Q: `use: X from other.yaml`에서 파일 경로의 기준점은 어디인가? → A: Jenkins workspace root 기준 상대 경로 (`other.yaml` → `$WORKSPACE/other.yaml`)
- Q: 중첩 `use` 디렉티브를 허용하는가? 순환 참조는 어떻게 처리하는가? → A: 중첩 허용. DAG 구조(동일 스테이지의 다중 참조)도 허용. 순환 참조(스테이지가 자신의 조상 체인에 등장)는 무조건 즉시 빌드 실패 (순환 경로 명시 오류 출력)
- Q: `makeStage: false` + `parallels` 타입 조합은 허용되는가? → A: 허용. 현재 스테이지 컨텍스트 안에서 `parallel()` 호출로 실행되며 Jenkins가 정상 지원함
