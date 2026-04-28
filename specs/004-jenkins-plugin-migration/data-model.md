# Data Model: Jenkins Plugin Migration

**Feature**: 004-jenkins-plugin-migration
**Date**: 2026-04-28 (Updated)

## Plugin Descriptor (pom.xml)

| Attribute | Value |
|-----------|-------|
| groupId | `io.jenkins.plugins` |
| artifactId | `stagefy` |
| version | `1.0.0-SNAPSHOT` |
| parent | `org.jenkins-ci.plugins:plugin:4.80` |
| jenkins.version | `2.440.3` |
| java.level | `21` |
| dependencies | workflow-step-api, workflow-cps, pipeline-utility-steps, snakeyaml 2.2 |

## StagefyGlobalVariable (Java — 유일한 Java 클래스)

`@Extension`으로 등록되는 `GlobalVariable` 구현체.

| 메서드 | 역할 |
|--------|------|
| `getName()` | `"stagefy"` 반환 — 파이프라인에서 `stagefy.xxx()` 형태로 접근 |
| `getValue(CpsScript)` | `StagefyDsl` 인스턴스를 생성하여 `CpsScript` binding에 등록 |

### Allowlist (내부 클래스)

`GroovySourceFileAllowlist` 구현. `/io/jenkins/plugins/stagefy/*.groovy` 패턴의 리소스를 Sandbox에서 허용.

## StagefyDsl (Groovy — 진입점)

| 메서드 | 파라미터 | 동작 |
|--------|----------|------|
| `run(file, stage)` | `String file`, `String stage` | DslJenkinsContext 생성 → YAML 로드 → StageFactory로 Stage 객체 생성 → `script.stage(name) { obj.run() }` |

## DslJenkinsContext (Groovy — CpsScript 래핑 어댑터)

기존 `JenkinsContext`와 동일한 인터페이스. 내부적으로 `CpsScript`에 위임.

| 메서드 | 위임 대상 |
|--------|-----------|
| `sh(cmd)` | `script.sh(cmd)` |
| `stage(name, body)` | `script.stage(name, body)` |
| `parallel(branches)` | `script.parallel(branches)` |
| `node(label, body)` | `script.node(label, body)` |
| `withEnv(envs, body)` | `script.withEnv(envs, body)` |
| `readYaml(file)` | `script.readYaml(file: file)` |
| `load(path)` | `script.load(path)` |
| `evaluate(value)` | `script.evaluate(value)` |
| `error(msg)` | `LOGGER.severe(msg)` + `script.error(msg)` |
| `getEnv(name)` | `script.env[name]` |
| `setEnvProperty(k, v)` | `script.env.setProperty(k, v)` |
| `log(msg)` | `script.println(msg)` |
| `debug/info/warn(msg)` | `LOGGER.log(Level.FINE/INFO/WARNING, msg)` |
| `loadData(file, stage)` | `readYaml(file)[stage]` |

## Stage 계층 (core/)

```
Stage (abstract, Serializable)
├── StepsStage      — steps 블록 실행, env/node 래핑
├── SequentialStage — stages 순차 실행, 동적 stage 생성
└── ParallelStage   — parallels 병렬 실행, jenkins.parallel()
```

| 공통 속성 | 타입 | 설명 |
|-----------|------|------|
| `_filename` | String | YAML 파일 경로 |
| `_stagename` | String | 스테이지 이름 |
| `_flag` | boolean | when 조건 평가 결과 |
| `_parent` | Stage | 부모 스테이지 (순환 참조 감지용) |
| `_jenkins` | DslJenkinsContext | Jenkins DSL 어댑터 |

## Step 계층 (core/)

```
Step (abstract, Serializable)
├── ShStep            — sh 명령어 실행 (EnvResolver로 변수 치환)
├── ScriptStep        — Groovy 스크립트 로드/실행
├── EvaluateStep      — Groovy 표현식 평가
├── SetEnvFromFileStep — YAML에서 환경 변수 로드
└── UseStageStep      — 다른 스테이지 참조 실행 (makeStage 옵션)
```

## 유틸리티 (util/)

| 클래스 | 역할 |
|--------|------|
| `EnvResolver` | `${env.VAR}` 패턴 추출 및 치환 |
| `ModuleResolver` | module load 셸 접두사 생성 |
| `StageNameBuilder` | 고유 스테이지 이름 생성 (sanitize, scalar, map, templateRef) |
| `TemplateResolver` | `{ARG}` 바인딩 치환 (재귀 깊이 5) |
| `IterationResolver` | `over:` 디렉티브를 리스트로 확장 (리스트/env.VAR) |
| `Template` | 템플릿 데이터 보유 및 검증 (arguments, steps) |

## Shared Library → Plugin 매핑

| Shared Library (vars/stagefy.groovy) | Plugin 위치 | 변경 사항 |
|--------------------------------------|-------------|-----------|
| `JenkinsContext` | `DslJenkinsContext.groovy` | `java.util.logging` 전환, `DEBUG_LEVEL` 폐기 |
| `EnvResolver` (@NonCPS) | `util/EnvResolver.groovy` | `@NonCPS` 제거 |
| `ModuleResolver` (@NonCPS) | `util/ModuleResolver.groovy` | `@NonCPS` 제거 |
| `StageNameBuilder` (@NonCPS) | `util/StageNameBuilder.groovy` | `@NonCPS` 제거 |
| `TemplateResolver` (@NonCPS) | `util/TemplateResolver.groovy` | `@NonCPS` 제거 |
| `IterationResolver` | `util/IterationResolver.groovy` | `JenkinsContext` → `def jenkins` |
| `Template` | `util/Template.groovy` | `JenkinsContext` → `def jenkins` |
| `Stage` (abstract) | `core/Stage.groovy` | private 필드 + getter 패턴 |
| `StepsStage` | `core/StepsStage.groovy` | 동일 |
| `SequentialStage` | `core/SequentialStage.groovy` | 동일 |
| `ParallelStage` | `core/ParallelStage.groovy` | 동일 |
| `Step` (abstract) | `core/Step.groovy` | private 필드 + getter 패턴 |
| `ShStep` ~ `UseStageStep` | `core/*.groovy` | 동일 |
| `StageFactory` | `core/StageFactory.groovy` | 동일 |
| `StepFactory` | `core/StepFactory.groovy` | 동일 |
| `run(file, stage)` | `StagefyDsl.run(file, stage)` | GlobalVariable 경유 |
| N/A (신규) | `StagefyGlobalVariable.java` | @Extension, Allowlist |
