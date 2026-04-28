# Quickstart: Jenkins Plugin Migration

**Feature**: 004-jenkins-plugin-migration
**Date**: 2026-04-28 (Updated)

## 개발 환경

```bash
export JAVA_HOME=/tools/java/openjdk/21
export PATH=$JAVA_HOME/bin:$PATH
java --version   # OpenJDK 21
mvn --version    # Maven 3.x
```

## 빌드

```bash
cd plugin/
mvn package          # HPI 생성
ls target/stagefy.hpi
```

## 테스트

```bash
mvn test             # JenkinsRule 통합 테스트
```

## 로컬 실행 (Docker)

```bash
cd plugin/
mvn package -DskipTests
docker build -t stagefy-test .
docker run -p 8080:8080 stagefy-test
```

## 사용법

기존 Shared Library:
```groovy
@Library('stagefy') _
stagefy.run('pipeline.yml', 'build')
```

플러그인 방식 (변경 최소화):
```groovy
// @Library 선언 불필요
stagefy.run('pipeline.yml', 'build')
```

호출 시그니처 `stagefy.run(file, stage)`는 동일하게 유지된다. `@Library` 선언만 제거하면 된다.
