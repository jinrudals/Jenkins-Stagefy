# Tasks: `use` Step Directive for External Stage Inclusion

**Input**: Design documents from `/specs/001-steps-use-directive/`
**Branch**: `001-steps-use-directive`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능 (파일이 다르거나 의존성 없음)
- **[Story]**: 해당 태스크가 속한 User Story (US1, US2)
- 변경 대상 파일은 `vars/stagefy.groovy` 단일 파일

---

## Phase 1: Setup

**Purpose**: 변경 대상 코드 파악 및 삽입 위치 확인

- [x] T001 `vars/stagefy.groovy`의 `steps_run()` 메서드(line 30-95)를 읽고, `content` 클로저 내 `for` 루프(line 44-65)에서 `use` 분기를 삽입할 위치 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US2 모두에서 공유하는 파싱·생성·순환참조 감지 핵심 로직 구현

**⚠️ CRITICAL**: 이 단계가 완료되기 전까지 US1·US2 구현을 시작할 수 없음

- [x] T002 `vars/stagefy.groovy` `steps_run()` `content` 클로저 내 `for` 루프에 `else if (each.containsKey("use"))` 분기를 추가. 분기 내용: (1) `each["use"]` 값에서 `" from "` 포함 여부 검증 — 없으면 `this.script.error("use directive must follow 'StageName from filepath' format: '${useValue}'")`; (2) `split(" from ", 2)`로 `targetStage`·`targetFile` 파싱; (3) `this.getClass().newInstance(targetFile, targetStage, this)`로 `childStage` 생성 (`this`를 parent로 전달); (4) `childStage.check_circular_loop(childStage)` 호출

**Checkpoint**: `use` 분기 진입 및 파싱·순환참조 감지 로직 동작 확인 후 US1·US2 진행 가능

---

## Phase 3: User Story 1 — `makeStage: true` (기본) 실행 (Priority: P1) 🎯 MVP

**Goal**: `use` 디렉티브 실행 시 Jenkins UI에 참조된 스테이지 이름으로 새 스테이지가 생성되어 실행된다

**Independent Test**: quickstart.md Example 1 패턴으로 테스트 Jenkins.yml을 작성하고, 빌드 후 Jenkins UI에 `build_all`, `build_a`, `build_b` 스테이지가 생성되는지 확인

### Implementation for User Story 1

- [x] T003 [US1] `vars/stagefy.groovy` T002에서 추가한 `use` 분기 안에 `makeStage` 처리 추가: `def makeStage = each.containsKey("makeStage") ? each["makeStage"] : true` → `if (makeStage) { this.script.stage(targetStage) { childStage.run() } }` 구현

- [x] T004 [US1] `quickstart.md` Example 1을 참고해 검증용 `examples/test_use_makestage.yaml` 작성: `build` 스테이지가 heredoc으로 `tasks.yaml` 생성 후 `use: build_all from tasks.yaml`을 호출하는 구조

**Checkpoint**: US1 독립 검증 — `test_use_makestage.yaml`을 `stagefy.run()`으로 실행 시 `build_all` 스테이지가 Jenkins UI에 표시되고 내부 steps가 정상 실행됨

---

## Phase 4: User Story 2 — `makeStage: false` 인라인 실행 (Priority: P2)

**Goal**: `makeStage: false` 지정 시 새 Jenkins 스테이지가 생성되지 않고 현재 스테이지 컨텍스트에서 인라인 실행된다

**Independent Test**: quickstart.md Example 2 패턴으로 테스트 Jenkins.yml을 작성하고, 빌드 후 Jenkins UI에 `init_env` 스테이지가 생성되지 않으면서 해당 steps가 실행되는지 확인

### Implementation for User Story 2

- [x] T005 [US2] `vars/stagefy.groovy` T003의 `if (makeStage)` 블록에 `else { childStage.run() }` 분기 추가 (인라인 실행 — stage 래핑 없음)

- [x] T006 [US2] `quickstart.md` Example 2를 참고해 검증용 `examples/test_use_inline.yaml` 작성: `setup` 스테이지가 heredoc으로 `init.yaml` 생성 후 `use: init_env from init.yaml` + `makeStage: false`를 호출하는 구조

**Checkpoint**: US2 독립 검증 — `test_use_inline.yaml`을 실행 시 Jenkins UI에 `init_env` 스테이지가 추가로 생성되지 않으면서 `init_env`의 steps 내용이 정상 실행됨

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 엣지 케이스 검증 및 공식 예시 추가

- [x] T007 `examples/Jenkins.yml`에 `use` 디렉티브 예시 스테이지 추가 — quickstart.md Example 1 기반 (heredoc으로 YAML 생성 후 `use` 호출)

- [x] T008 DAG 시나리오 검증: quickstart.md Example 3 패턴(동일 스테이지 다중 참조)으로 테스트 파이프라인을 실행하고 순환 참조 오류가 발생하지 않음을 확인

- [x] T009 순환 참조 감지 검증: A→B→A 패턴의 테스트 YAML을 만들어 실행하고 `Circular Loop Execution` 오류 메시지가 정확히 출력되는지 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능
- **Foundational (Phase 2)**: Phase 1 완료 후 — US1·US2 모두 블록
- **US1 (Phase 3)**: Phase 2 완료 후 시작 가능
- **US2 (Phase 4)**: Phase 2 완료 후 시작 가능 (US1과 병렬 가능)
- **Polish (Phase 5)**: US1·US2 모두 완료 후

### Task Dependencies

```
T001 → T002 → T003 → T004    (US1 경로)
              T002 → T005 → T006    (US2 경로, T002 이후 T003과 병렬 가능)
T004, T006 → T007, T008, T009
```

### Parallel Opportunities

- T003(US1)과 T005(US2)는 같은 파일·같은 블록의 if/else이므로 순서가 있음 — T003 먼저 구현 후 T005
- T007·T008·T009는 서로 독립적으로 병렬 실행 가능

---

## Implementation Strategy

### MVP (User Story 1만)

1. T001 → T002 완료 (파싱·생성 기반)
2. T003 → T004 완료 (makeStage: true 동작 확인)
3. **STOP and VALIDATE**: `test_use_makestage.yaml` Jenkins 빌드 실행, 새 스테이지 생성 확인
4. MVP 배포 가능 — `makeStage: true` 기능 사용 가능

### Incremental Delivery

1. T001~T004 완료 → US1 독립 검증 → **MVP 릴리즈**
2. T005~T006 완료 → US2 독립 검증
3. T007~T009 완료 → 엣지 케이스 검증 및 공식 예시 추가

---

## Notes

- 변경 파일: `vars/stagefy.groovy` 단일 파일 (`steps_run()` 메서드 내 약 15줄 추가)
- 검증 파일: `examples/test_use_makestage.yaml`, `examples/test_use_inline.yaml` (임시 검증용)
- T002의 `childStage.check_circular_loop(childStage)` 호출이 핵심 — `steps` 타입은 `run()`에서 자동 호출되지 않으므로 명시 필요
- `this.getClass().newInstance(...)` 사용 이유: `construct_stage()`는 `parent = null`로 생성하므로 순환 참조 체인이 끊김
