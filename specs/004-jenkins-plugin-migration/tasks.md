# Tasks: Jenkins Plugin으로 전환

**Input**: Design documents from `/specs/004-jenkins-plugin-migration/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- Plugin source: `plugin/src/main/java/io/jenkins/plugins/stagefy/`
- Plugin Groovy resources: `plugin/src/main/resources/io/jenkins/plugins/stagefy/`
- Plugin tests: `plugin/src/test/java/io/jenkins/plugins/stagefy/`
- Test resources: `plugin/src/test/resources/`

---

## Phase 1: Setup

**Purpose**: 빌드 환경 확인 및 프로젝트 구조 검증

- [x] T001 Java 21 및 Maven 환경 확인 (`/tools/java/openjdk/21/bin/java --version`, `mvn --version`)
- [x] T002 `plugin/pom.xml` 의존성 검증 — workflow-step-api, workflow-cps, pipeline-utility-steps, snakeyaml 2.2 확인

---

## Phase 2: Foundational (버그 수정 — 모든 User Story 차단)

**Purpose**: 기존 코드의 패키지/import/생성자 오류 수정. 이 Phase가 완료되어야 빌드 및 모든 기능이 동작함.

**⚠️ CRITICAL**: 빌드 자체가 실패하므로 이 Phase 완료 전까지 어떤 User Story도 진행 불가

- [x] T003 [P] 패키지 선언 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ShStep.groovy`
- [x] T004 [P] 패키지 선언 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ScriptStep.groovy`
- [x] T005 [P] 패키지 선언 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/EvaluateStep.groovy`
- [x] T006 [P] 패키지 선언 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/SetEnvFromFileStep.groovy`
- [x] T007 [P] 패키지 선언 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/UseStageStep.groovy`
- [x] T008 [P] 패키지 선언 및 import 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core`, `io.getJenkins().plugins.stagefy.util.*` → `io.jenkins.plugins.stagefy.util.*` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/SequentialStage.groovy`
- [x] T009 [P] 패키지 선언 및 import 수정 — `io.getJenkins().plugins.stagefy.core` → `io.jenkins.plugins.stagefy.core`, `io.getJenkins().plugins.stagefy.util.*` → `io.jenkins.plugins.stagefy.util.*` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ParallelStage.groovy`
- [x] T010 [P] 생성자 파라미터 수정 — `String getModuleprefix()` → `String moduleprefix` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ShStep.groovy`
- [x] T011 [P] 생성자 파라미터 수정 — `String getModuleprefix()` → `String moduleprefix` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ScriptStep.groovy`
- [x] T012 [P] 생성자 파라미터 수정 — `String getModuleprefix()` → `String moduleprefix` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/EvaluateStep.groovy`
- [x] T013 [P] 생성자 파라미터 수정 — `String getModuleprefix()` → `String moduleprefix` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/SetEnvFromFileStep.groovy`
- [x] T014 [P] 생성자 파라미터 수정 — `String getModuleprefix()` → `String moduleprefix` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/UseStageStep.groovy`
- [x] T015 [P] import 수정 — `io.getJenkins().plugins.stagefy.util.EnvResolver` → `io.jenkins.plugins.stagefy.util.EnvResolver` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ShStep.groovy`
- [x] T016 [P] private 필드 참조 수정 — `tpl.validate(jenkins)` → `tpl.validate(getJenkins())` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/SequentialStage.groovy` (buildTemplateStage, buildIteratedStages 메서드 내 모든 `jenkins` 직접 참조)
- [x] T017 [P] private 필드 참조 수정 — `tpl.validate(jenkins)` → `tpl.validate(getJenkins())` in `plugin/src/main/resources/io/jenkins/plugins/stagefy/core/ParallelStage.groovy` (buildTemplateStage, buildIteratedStages 메서드 내 모든 `jenkins` 직접 참조)
- [x] T018 Maven 빌드 검증 — `cd plugin && mvn package -DskipTests` 실행하여 HPI 생성 확인 (`plugin/target/stagefy.hpi`)

**Checkpoint**: `mvn package -DskipTests` 성공, `stagefy.hpi` 생성됨

---

## Phase 3: User Story 1 — 보안 설정 없이 즉시 사용 (Priority: P1) 🎯 MVP

**Goal**: 플러그인 설치만으로 Script Security 승인 없이 모든 Stagefy 기능이 동작

**Independent Test**: 깨끗한 Jenkins 인스턴스에 HPI 설치 후, Script Approval 없이 `stagefy.run('test.yml', 'build')` 실행 성공

### Implementation for User Story 1

- [ ] T019 [US1] `StagefyGlobalVariable.java`의 `Allowlist.isAllowed()` 검증 — 플러그인 JAR 내 모든 `.groovy` 리소스가 Sandbox 허용 목록에 포함되는지 확인 in `plugin/src/main/java/io/jenkins/plugins/stagefy/StagefyGlobalVariable.java`
- [ ] T020 [US1] JenkinsRule 통합 테스트 작성 — GlobalVariable 등록 확인 및 기본 `stagefy.run()` 호출 테스트 in `plugin/src/test/java/io/jenkins/plugins/stagefy/StagefyGlobalVariableTest.java`
- [ ] T021 [P] [US1] 테스트용 YAML 리소스 작성 — 기본 steps stage in `plugin/src/test/resources/test-basic.yml`
- [ ] T022 [US1] JenkinsRule 테스트 실행 — `cd plugin && mvn test` 통과 확인
- [ ] T023 [US1] Docker E2E 검증 — `cd plugin && mvn package -DskipTests && docker build -t stagefy-test . && docker run -p 8080:8080 stagefy-test`로 Jenkins 기동 후 Script Approval 페이지에 승인 요청이 0건인지 확인

**Checkpoint**: Script Security 승인 없이 기본 파이프라인 실행 성공

---

## Phase 4: User Story 2 — 기존 파이프라인 호환성 유지 (Priority: P1)

**Goal**: 기존 Jenkinsfile과 YAML 설정 파일이 플러그인 방식에서 동일하게 동작

**Independent Test**: 기존 예제 YAML(`examples/Jenkins.yml`)의 stages, parallels, steps, templates, use directive가 플러그인에서 정상 실행

### Implementation for User Story 2

- [ ] T024 [P] [US2] 테스트용 YAML 리소스 작성 — sequential stages in `plugin/src/test/resources/test-sequential.yml`
- [ ] T025 [P] [US2] 테스트용 YAML 리소스 작성 — parallel stages in `plugin/src/test/resources/test-parallel.yml`
- [ ] T026 [P] [US2] 테스트용 YAML 리소스 작성 — template + iterated in `plugin/src/test/resources/test-template.yml`
- [ ] T027 [P] [US2] 테스트용 YAML 리소스 작성 — use directive in `plugin/src/test/resources/test-use.yml`
- [ ] T028 [P] [US2] 테스트용 YAML 리소스 작성 — 환경 변수 치환 (`${env.VAR}`) in `plugin/src/test/resources/test-env.yml`
- [ ] T029 [US2] JenkinsRule 호환성 테스트 작성 — SequentialStage, ParallelStage, StepsStage, Template, UseStageStep, 환경 변수 치환 각각 검증 in `plugin/src/test/java/io/jenkins/plugins/stagefy/StagefyCompatibilityTest.java`
- [ ] T030 [US2] 에러 케이스 테스트 작성 — 파일 없음, 스테이지 없음, 순환 참조 감지 in `plugin/src/test/java/io/jenkins/plugins/stagefy/StagefyErrorTest.java`
- [ ] T031 [US2] `cd plugin && mvn test` 전체 테스트 통과 확인

**Checkpoint**: 모든 YAML 기능(stages, parallels, steps, templates, use, env)이 플러그인에서 동작

---

## Phase 5: User Story 3 — 플러그인 설치 및 업데이트 (Priority: P2)

**Goal**: 표준 Jenkins Plugin Manager를 통해 HPI 설치/업데이트/제거 가능

**Independent Test**: Jenkins Plugin Manager에서 HPI 업로드 → 재시작 → 플러그인 목록에 Stagefy 표시 → 제거 후 깔끔하게 삭제

### Implementation for User Story 3

- [ ] T032 [US3] 플러그인 메타데이터 검증 — `plugin/pom.xml`의 `<name>`, `<version>`, `<description>` 필드가 Plugin Manager UI에 올바르게 표시되는지 확인
- [ ] T033 [US3] Docker E2E 검증 — HPI 업로드 설치 → Jenkins 재시작 → Plugin Manager에서 Stagefy 확인 → 파이프라인 실행 → 플러그인 제거 후 정상 동작 확인

**Checkpoint**: 표준 Plugin Manager 워크플로우로 설치/제거 가능

---

## Phase 6: User Story 4 — 플러그인 전용 Pipeline Step 제공 (Priority: P2)

**Goal**: `@Library` 선언 없이 `stagefy.run(file, stage)` 형태로 YAML 기반 스테이지 실행

**Independent Test**: `@Library` 선언이 없는 Jenkinsfile에서 `stagefy.run('pipeline.yml', 'build')` 호출 성공

### Implementation for User Story 4

- [ ] T034 [US4] `@Library` 없는 Jenkinsfile 테스트 — JenkinsRule에서 `@Library` 선언 없이 `stagefy.run()` 호출이 GlobalVariable을 통해 정상 동작하는지 검증 in `plugin/src/test/java/io/jenkins/plugins/stagefy/StagefyNoLibraryTest.java`
- [ ] T035 [US4] Declarative Pipeline 호환 테스트 — `pipeline { stages { stage('x') { steps { script { stagefy.run(...) } } } } }` 형태 검증 in `plugin/src/test/java/io/jenkins/plugins/stagefy/StagefyNoLibraryTest.java`
- [ ] T036 [US4] `cd plugin && mvn test` 전체 테스트 통과 확인

**Checkpoint**: `@Library` 없이 Declarative/Scripted Pipeline 모두에서 `stagefy.run()` 동작

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 문서화, 의존성 에러 처리, 최종 검증

- [ ] T037 [P] 의존성 에러 메시지 구현 — 필수 플러그인(workflow-cps 등) 미설치 시 명확한 에러 표시 확인 (pom.xml `<dependencies>` 기반 자동 처리 검증)
- [ ] T038 [P] `plugin/Jenkinsfile` 업데이트 — 플러그인 CI 파이프라인에 `mvn test` 단계 추가 in `plugin/Jenkinsfile`
- [ ] T039 quickstart.md 검증 — `specs/004-jenkins-plugin-migration/quickstart.md`의 빌드/테스트/실행 절차를 실제로 따라가며 검증

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 즉시 시작 가능
- **Phase 2 (Foundational)**: Phase 1 완료 후 — **모든 User Story 차단**
- **Phase 3 (US1)**: Phase 2 완료 후
- **Phase 4 (US2)**: Phase 3 완료 후 (US1의 기본 테스트 인프라 재사용)
- **Phase 5 (US3)**: Phase 2 완료 후 (US1/US2와 독립적으로 진행 가능)
- **Phase 6 (US4)**: Phase 3 완료 후 (GlobalVariable 동작 확인 필요)
- **Phase 7 (Polish)**: 모든 User Story 완료 후

### User Story Dependencies

- **US1 (보안 설정 제거)**: Phase 2 완료 후 즉시 시작 — 다른 Story 의존 없음
- **US2 (호환성)**: US1 완료 후 — US1의 테스트 인프라 활용
- **US3 (설치/업데이트)**: Phase 2 완료 후 — US1/US2와 독립
- **US4 (@Library 제거)**: US1 완료 후 — GlobalVariable 동작 전제

### Parallel Opportunities

```
Phase 2: T003~T017 모두 병렬 가능 (서로 다른 파일)
Phase 3+4 완료 후: US3(Phase 5)와 US4(Phase 6) 병렬 가능
Phase 4: T024~T028 모두 병렬 가능 (테스트 YAML 파일 생성)
```

---

## Parallel Example: Phase 2 (버그 수정)

```
# 모든 패키지/import/생성자 수정을 동시에 실행:
T003: ShStep.groovy 패키지 수정
T004: ScriptStep.groovy 패키지 수정
T005: EvaluateStep.groovy 패키지 수정
T006: SetEnvFromFileStep.groovy 패키지 수정
T007: UseStageStep.groovy 패키지 수정
T008: SequentialStage.groovy 패키지+import 수정
T009: ParallelStage.groovy 패키지+import 수정
T010~T015: 생성자 파라미터 및 import 수정
T016~T017: private 필드 참조 수정
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup 확인
2. Phase 2: 버그 수정 + 빌드 검증 (**CRITICAL**)
3. Phase 3: US1 — Script Security 승인 없이 기본 파이프라인 실행
4. **STOP and VALIDATE**: Docker E2E로 Script Approval 0건 확인
5. MVP 배포 가능

### Incremental Delivery

1. Setup + Foundational → 빌드 성공
2. US1 (보안 설정 제거) → MVP 배포
3. US2 (호환성) → 기존 파이프라인 마이그레이션 시작
4. US3 (설치/업데이트) + US4 (@Library 제거) → 병렬 진행
5. Polish → 최종 릴리스

---

## Notes

- T003~T017은 모두 동일 패턴의 텍스트 치환이므로 한 번에 일괄 수정 가능
- JenkinsRule 테스트는 임베디드 Jenkins를 띄우므로 실행 시간이 길 수 있음 (개별 테스트 ~30초)
- Docker E2E 테스트(T023, T033)는 수동 검증 단계 — CI 자동화는 Phase 7에서 고려
