# Research: Jenkins Plugin Migration

**Feature**: 004-jenkins-plugin-migration
**Date**: 2026-04-28 (Updated)

## R1: Plugin 아키텍처 — GlobalVariable vs AbstractStepExecution

**Decision**: `GlobalVariable` + CPS Groovy 리소스 방식 채택. Java는 `@Extension` 등록만, Groovy 리소스가 CPS 컨텍스트 내에서 Pipeline DSL을 호출한다.

**Rationale**: Jenkins Pipeline의 `stage()`, `parallel()`, `node()` DSL은 CPS 변환된 Groovy 실행 컨텍스트에서만 동작한다. Java `StepExecution` 내부에서는 이 DSL들을 호출할 수 없으므로, Stagefy의 핵심 기능인 동적 stage 생성이 불가능하다. `GlobalVariable`은 `CpsScript`를 직접 전달받아 Groovy 리소스에서 DSL을 자유롭게 호출할 수 있다.

**Alternatives considered**:
- `AbstractSynchronousNonBlockingStepExecution` → `stage()` DSL 호출 불가. `StepContext`에서 Pipeline DSL에 접근하는 공식 API가 없음. Reflection으로 `CpsThread`에 접근하는 해킹적 방법만 존재하나 극도로 불안정
- `AbstractStepExecutionImpl` + `BodyInvoker` → body를 동적으로 생성하는 것은 가능하나, 여러 stage를 순차/병렬로 생성하는 복잡한 패턴에는 부적합
- Java로 완전 재작성 → 비용 과다, CPS 제약으로 stage 생성 자체가 불가

## R2: GroovySourceFileAllowlist를 통한 Sandbox 우회

**Decision**: `StagefyGlobalVariable.Allowlist` 내부 클래스에서 `GroovySourceFileAllowlist`를 구현하여 플러그인 JAR 내 모든 `.groovy` 리소스를 허용한다.

**Rationale**: Jenkins의 Script Security는 CPS 스크립트 실행 시 Sandbox를 적용한다. 플러그인 리소스로 포함된 Groovy 파일은 기본적으로 Sandbox 대상이지만, `GroovySourceFileAllowlist`에 등록하면 Sandbox를 우회할 수 있다. 이를 통해 `new` 키워드, 메타프로그래밍, 내부 API 접근 등이 승인 없이 가능해진다.

**Alternatives considered**:
- `@Whitelisted` 어노테이션 → 개별 메서드마다 적용해야 하므로 유지보수 부담 큼
- Sandbox 완전 비활성화 → Jenkins 전체 보안에 영향, 다른 파이프라인에도 적용됨

## R3: CPS 호환성 — @NonCPS 제거

**Decision**: 기존 Shared Library의 `@NonCPS` 어노테이션을 모두 제거한다. 플러그인 리소스는 `GroovySourceFileAllowlist`로 허용되므로 CPS 변환 제약이 적용되지 않는다.

**Rationale**: `@NonCPS`는 Sandbox 환경에서 CPS 변환을 우회하기 위해 사용했다. 플러그인 리소스는 Allowlist로 허용되어 CPS 변환 대상에서 제외되므로 `@NonCPS`가 불필요하다. 단, `DslJenkinsContext`를 통한 DSL 호출(`stage()`, `parallel()` 등)은 CPS 컨텍스트에서 실행되므로 정상 동작한다.

**Alternatives considered**:
- `@NonCPS` 유지 → 불필요한 어노테이션이 코드 가독성을 저해

## R4: JenkinsContext 어댑터 — CpsScript 래핑

**Decision**: `DslJenkinsContext`가 `CpsScript`를 래핑하여 기존 `JenkinsContext`와 동일한 인터페이스를 제공한다. `stage()`, `parallel()`, `sh()`, `readYaml()` 등 모든 DSL 호출을 `script.xxx()` 형태로 위임한다.

**Rationale**: 기존 Stage/Step 계층이 `JenkinsContext` 인터페이스에 의존하므로, 동일한 메서드 시그니처를 유지하면 코드 변경을 최소화할 수 있다. `CpsScript`는 CPS 컨텍스트 내에서 모든 Pipeline DSL에 접근 가능하다.

**Alternatives considered**:
- `StepContext` 기반 재구현 → `stage()` DSL 호출 불가로 폐기
- Stage/Step 계층에서 `CpsScript` 직접 사용 → 결합도 증가, 테스트 어려움

## R5: 로깅 전환

**Decision**: `java.util.logging.Logger`를 사용하고, 콘솔 출력은 `script.println()`을 통해 유지한다. 기존 `DEBUG_LEVEL` 환경 변수 방식은 폐기한다.

**Rationale**: Jenkins 표준 로깅은 Log Recorder UI와 통합되어 운영 시 디버깅이 용이하다. `DslJenkinsContext`에서 `LOGGER.log(Level.FINE/INFO/WARNING, msg)` 형태로 구현 완료.

**Alternatives considered**:
- 기존 `DEBUG_LEVEL` 유지 → spec clarification에서 폐기 결정

## R6: 테스트 전략

**Decision**: JenkinsRule 기반 통합 테스트를 주력으로 하고, Docker 기반 E2E 테스트를 보조로 사용한다.

**Rationale**: `JenkinsRule`은 임베디드 Jenkins를 띄워 실제 파이프라인 실행을 테스트할 수 있다. `GlobalVariable`이 정상 등록되고, Groovy 리소스가 로드되며, CPS 컨텍스트에서 stage가 생성되는 전체 흐름을 검증할 수 있다.

**Alternatives considered**:
- 순수 단위 테스트만 → CPS 컨텍스트 내 DSL 호출을 검증할 수 없음
- Docker 테스트만 → CI에서 느리고 피드백 루프가 김

## R7: 배포 방식

**Decision**: Maven으로 HPI 빌드 후 사내 Jenkins에 수동 업로드 또는 CLI로 배포한다.

**Rationale**: 초기 범위에서 Jenkins Update Center 등록은 제외. HPI 파일 직접 업로드가 가장 단순하다.
