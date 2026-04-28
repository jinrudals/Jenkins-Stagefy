# Feature Specification: Jenkins Plugin으로 전환

**Feature Branch**: `004-jenkins-plugin-migration`  
**Created**: 2026-04-28  
**Status**: Draft  
**Input**: User description: "Shared Groovy Library에서 Jenkins Plugin으로 전환하여 초기 Security 설정 부담 제거"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 보안 설정 없이 즉시 사용 (Priority: P1)

Jenkins 관리자가 Stagefy를 처음 도입할 때, 현재는 Shared Groovy Library 방식이라 Script Security 플러그인의 승인(Script Approval), In-process Script Approval, 메서드 화이트리스트 등 다수의 보안 설정을 수동으로 해줘야 한다. 구체적으로 다음과 같은 기능들이 Sandbox에서 차단되어 개별 승인이 필요하다:

- 런타임 동적 Stage 생성 (`stage()` DSL의 동적 호출)
- `@NonCPS` 어노테이션이 붙은 메서드 실행 (CPS 변환 제외 메서드)
- `new` 키워드를 통한 클래스 인스턴스화 (JenkinsContext, EnvResolver, Stage/Step 계층 등)
- Groovy 메타프로그래밍 기능 (Closure, Map.collect 등 화이트리스트 미등록 메서드)
- Jenkins 내부 API 접근 (`org.jenkinsci.plugins.pipeline.modeldefinition.Utils` 등)

이러한 항목들이 수십 개에 달해 초기 설정 부담이 매우 크다. Jenkins Plugin으로 전환하면 플러그인 코드가 Sandbox 밖에서 실행되므로 이 모든 승인이 불필요해진다.

**Why this priority**: 현재 가장 큰 도입 장벽이 초기 보안 설정의 복잡성이다. 이것이 해결되지 않으면 전환의 핵심 가치가 없다.

**Independent Test**: 새로운 Jenkins 인스턴스에 플러그인을 설치한 후, 별도의 Script Security 승인 없이 기존 YAML 기반 파이프라인이 정상 실행되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 깨끗한 Jenkins 인스턴스, **When** Stagefy 플러그인을 설치하면, **Then** 추가적인 Script Approval이나 보안 설정 없이 Stagefy 기능을 사용할 수 있다
2. **Given** 기존에 Script Security 승인이 필요했던 Groovy 메서드 호출, **When** 플러그인 내부에서 실행되면, **Then** 별도 승인 없이 정상 동작한다
3. **Given** Jenkins 보안 정책이 기본값(Sandbox 활성화)인 환경, **When** Stagefy 플러그인을 사용하면, **Then** Sandbox 제약 없이 모든 기능이 동작한다

---

### User Story 2 - 기존 파이프라인 호환성 유지 (Priority: P1)

기존 Stagefy Shared Library를 사용하는 팀이 플러그인으로 전환할 때, 기존 Jenkinsfile과 YAML 설정 파일을 최소한의 변경으로 계속 사용할 수 있어야 한다.

**Why this priority**: 기존 사용자의 마이그레이션 비용이 높으면 전환 자체가 불가능하다. 기존 파이프라인과의 호환성은 채택의 필수 조건이다.

**Independent Test**: 기존 예제 Jenkinsfile(Jenkinsfile, Jenkinsfile_oop, Jenkinsfile.test_use 등)과 YAML 설정 파일이 플러그인 방식에서도 동작하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 기존 `stagefy.run(filename, stagename)` 호출을 사용하는 Jenkinsfile, **When** 플러그인 방식으로 전환하면, **Then** 호출 방식의 변경이 최소화되고 동일한 실행 결과를 얻는다
2. **Given** 기존 YAML 설정 파일(stages, parallels, steps, templates), **When** 플러그인에서 로드하면, **Then** 동일한 구조로 파싱되고 실행된다
3. **Given** 환경 변수 치환(`${env.VAR}`)을 사용하는 설정, **When** 플러그인에서 실행하면, **Then** 기존과 동일하게 변수가 치환된다

---

### User Story 3 - 플러그인 설치 및 업데이트 (Priority: P2)

Jenkins 관리자가 표준 Jenkins 플러그인 관리 방식(Plugin Manager UI 또는 CLI)을 통해 Stagefy를 설치, 업데이트, 제거할 수 있어야 한다.

**Why this priority**: 표준 플러그인 배포 방식을 따라야 운영팀이 기존 플러그인 관리 워크플로우에 통합할 수 있다.

**Independent Test**: Jenkins Plugin Manager에서 HPI 파일을 업로드하여 설치하고, 재시작 후 정상 동작하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** Stagefy HPI 파일, **When** Jenkins Plugin Manager에서 업로드하면, **Then** 플러그인이 설치되고 재시작 후 사용 가능하다
2. **Given** 설치된 Stagefy 플러그인, **When** 새 버전의 HPI를 업로드하면, **Then** 기존 설정을 유지하면서 업데이트된다
3. **Given** 설치된 Stagefy 플러그인, **When** Plugin Manager에서 제거하면, **Then** 깔끔하게 제거되고 다른 플러그인에 영향을 주지 않는다

---

### User Story 4 - 플러그인 전용 Pipeline Step 제공 (Priority: P2)

파이프라인 작성자가 Stagefy 플러그인이 제공하는 커스텀 Pipeline Step(예: `stagefy`)을 사용하여 YAML 기반 스테이지를 실행할 수 있어야 한다. 이는 Shared Library의 `@Library` 선언을 대체한다.

**Why this priority**: 플러그인 네이티브 Step은 `@Library` 선언 없이 사용 가능하여 Jenkinsfile을 더 간결하게 만들고, 자동완성 등 IDE 지원도 가능하게 한다.

**Independent Test**: `@Library` 선언 없이 `stagefy` Step만으로 YAML 기반 파이프라인을 실행할 수 있는지 확인한다.

**Acceptance Scenarios**:

1. **Given** Stagefy 플러그인이 설치된 Jenkins, **When** Jenkinsfile에서 `stagefy(file: 'pipeline.yml', stage: 'build')` 형태로 호출하면, **Then** 해당 YAML의 스테이지가 실행된다
2. **Given** 플러그인 Step 호출, **When** 기존 YAML 설정의 모든 기능(parallel, sequential, templates, use directive)을 사용하면, **Then** Shared Library 방식과 동일하게 동작한다

---

### Edge Cases

- 플러그인과 기존 Shared Library가 동시에 설치된 경우 충돌이 발생하지 않아야 한다
- Jenkins LTS와 최신 Weekly 버전 모두에서 호환되어야 한다
- Pipeline: Groovy 플러그인 등 필수 의존 플러그인이 없을 때 명확한 에러 메시지를 표시해야 한다
- CPS(Continuation Passing Style) 변환 환경에서 플러그인 코드가 정상 동작해야 한다
- Jenkins 재시작 후에도 진행 중이던 파이프라인이 올바르게 복구되어야 한다

## Clarifications

### Session 2026-04-28

- Q: 플러그인 자체의 접근 제어 모델은? → A: Jenkins 기본 Job 권한에 위임 (Job 실행 권한이 있으면 stagefy Step 사용 가능, 플러그인 전용 Permission 불필요)
- Q: Shared Library와의 병행 기간 마이그레이션 전략은? → A: 플러그인 우선 — Shared Library는 버그 수정만 유지, 신규 기능은 플러그인에만 추가, 6개월 후 Shared Library 폐기
- Q: 플러그인 로깅 방식은? → A: Jenkins 표준 로깅(java.util.logging)으로 완전 전환, 기존 DEBUG_LEVEL 환경 변수 방식 폐기
- Q: Java로 동적 stage 생성이 가능한가? → A: 불가. `stage()` DSL은 CPS 변환된 Groovy 컨텍스트에서만 동작하므로, 동적 stage 생성 로직은 반드시 Groovy(플러그인 리소스)로 구현해야 한다. Java는 플러그인 등록(@Extension, GlobalVariable)과 Groovy 소스 허용(GroovySourceFileAllowlist)에만 사용한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 표준 Jenkins 플러그인(HPI) 형태로 패키징되어 Plugin Manager를 통해 설치/업데이트/제거가 가능해야 한다
- **FR-002**: 시스템은 별도의 Script Security 승인 없이 모든 Stagefy 기능(YAML 파싱, 스테이지 실행, 환경 변수 치환, 템플릿 처리)을 제공해야 한다
- **FR-003**: 시스템은 커스텀 Pipeline Step(`stagefy`)을 제공하여 `@Library` 선언 없이 YAML 기반 스테이지를 실행할 수 있어야 한다
- **FR-004**: 시스템은 기존 YAML 설정 파일 형식과 100% 호환되어야 한다 (stages, parallels, steps, templates, use directive, 환경 변수 치환)
- **FR-005**: 시스템은 Jenkins CPS(Continuation Passing Style) 환경과 호환되어야 한다. `stage()`, `parallel()`, `node()` 등 Pipeline DSL 호출은 CPS 변환된 Groovy 컨텍스트에서만 동작하므로, 동적 stage 생성을 포함한 파이프라인 실행 로직은 반드시 Groovy(플러그인 리소스 파일)로 구현해야 한다. Java는 플러그인 등록(`@Extension`, `GlobalVariable`)과 Groovy 소스 허용(`GroovySourceFileAllowlist`)에만 사용한다.
- **FR-006**: 시스템은 필수 의존 플러그인(Pipeline, Pipeline: Groovy 등)이 없을 때 설치 시점에 명확한 의존성 에러를 표시해야 한다
- **FR-007**: 시스템은 기존 OOP 아키텍처(Stage Layer, Step Layer, Template System, JenkinsContext)를 플러그인 내부에서 유지해야 한다
- **FR-008**: 시스템은 Jenkins 표준 로깅 체계(`java.util.logging`)를 사용하여 로그를 출력해야 하며, 기존 `DEBUG_LEVEL` 환경 변수 방식은 폐기한다
- **FR-009**: 시스템은 별도의 플러그인 전용 Permission을 추가하지 않으며, Jenkins 기본 Job 실행 권한에 접근 제어를 위임해야 한다

### Key Entities

- **Plugin Descriptor**: 플러그인 메타데이터(이름, 버전, 의존성, 호환 Jenkins 버전)를 정의하는 엔티티
- **Pipeline Step Definition**: `stagefy` 커스텀 Step의 파라미터, 실행 로직, 반환값을 정의하는 엔티티
- **Stage Hierarchy**: 기존 StepsStage, SequentialStage, ParallelStage 클래스 구조를 플러그인 내부로 이전한 엔티티
- **Step Hierarchy**: 기존 ShStep, ScriptStep, UseStageStep 등 Step 클래스 구조

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 새로운 Jenkins 인스턴스에서 플러그인 설치 후 5분 이내에 첫 번째 Stagefy 파이프라인을 실행할 수 있다 (현재 Shared Library 방식 대비 보안 설정 시간 제거)
- **SC-002**: 기존 Jenkinsfile과 YAML 설정 파일의 90% 이상이 수정 없이 또는 `@Library` 선언 제거만으로 플러그인 방식에서 동작한다
- **SC-003**: Script Security 관련 승인 요청이 0건이다 (플러그인 설치 후 추가 보안 설정 불필요)
- **SC-004**: 플러그인 설치부터 파이프라인 실행까지의 단계가 3단계 이하이다 (설치 → 재시작 → 파이프라인 실행)
- **SC-005**: 기존 Shared Library 방식과 플러그인 방식의 파이프라인 실행 시간 차이가 10% 이내이다

## Assumptions

- Jenkins LTS 2.387.x 이상을 최소 지원 버전으로 한다
- 기존 OOP 리팩토링(003-oop-refactoring)이 완료된 코드베이스를 기반으로 플러그인을 개발한다
- 플러그인은 사내 Jenkins 인스턴스에 HPI 파일로 직접 배포하며, Jenkins 공식 Update Center 등록은 초기 범위에 포함하지 않는다
- 기존 Shared Library 방식도 당분간 병행 지원하여 점진적 마이그레이션을 가능하게 한다
- 마이그레이션 전략: 플러그인 우선 정책 — Shared Library는 버그 수정만 유지하고 신규 기능은 플러그인에만 추가하며, 플러그인 출시 6개월 후 Shared Library를 폐기한다
- Maven 기반 Jenkins 플러그인 빌드 시스템을 사용한다
- Pipeline 관련 핵심 플러그인(workflow-step-api, workflow-cps 등)이 Jenkins에 설치되어 있다고 가정한다
- **기술 제약 — Java/Groovy 역할 분리**: Jenkins Pipeline의 `stage()` DSL은 CPS 변환된 Groovy 실행 컨텍스트에서만 동작하므로, Java 코드(`StepExecution` 등)에서 직접 `stage()`를 호출하여 동적 stage를 생성할 수 없다. 따라서 플러그인 아키텍처는 Java(플러그인 등록, `@Extension`, `GlobalVariable`, `GroovySourceFileAllowlist`)와 Groovy 리소스(CPS 컨텍스트 내 DSL 호출, 동적 stage/parallel/node 생성)로 역할을 분리한다.
