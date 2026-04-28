/*
YAML 기반 Jenkins 스테이지 로더.
OOP 리팩토링 아키텍처:
  - 스테이지 계층: StepsStage, SequentialStage, ParallelStage
  - 스텝 계층: ShStep, ScriptStep, UseStageStep 등
  - 템플릿 시스템: 재귀 변수 치환 (깊이 제한 5)
  - JenkinsContext 어댑터: 모든 DSL 호출 래핑
  - DEBUG_LEVEL 환경 변수: 실행 추적 로깅 (debug/info/warn/error)
*/
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import groovy.json.JsonSlurper

// ============================================================================
// Jenkins 어댑터
// ============================================================================

/**
 * Jenkins 파이프라인 DSL 호출을 래핑하는 어댑터.
 * 테스트 및 추상화를 위해 모든 DSL 호출을 중앙 집중화.
 * CPS 호환을 위해 Serializable 구현.
 */
class JenkinsContext implements Serializable {
  def script

  JenkinsContext(script) { this.script = script }

  def stage(String name, Closure body) { script.stage(name, body) }
  def sh(String cmd) { script.sh(cmd) }
  def parallel(Map branches) { script.parallel(branches) }
  def node(String label, Closure body) { script.node(label, body) }
  def withEnv(List envs, Closure body) { script.withEnv(envs, body) }
  def readYaml(String file) { script.readYaml(file: file) }
  def load(String path) { script.load(path) }
  def evaluate(value) { script.evaluation(value) }
  def error(String msg) { script.error(msg) }
  def getEnv(String name) { script.env[name] }
  def setEnvProperty(String k, v) { script.env.setProperty(k, v) }
  def log(String msg) { script.println(msg) }
  def debug(String msg) { if (logLevel <= 0) script.println("[STAGEFY DEBUG] ${msg}") }
  def info(String msg)  { if (logLevel <= 1) script.println("[STAGEFY INFO] ${msg}") }
  def warn(String msg)  { if (logLevel <= 2) script.println("[STAGEFY WARN] ${msg}") }

  int getLogLevel() {
    try {
      def dl = script.env['DEBUG_LEVEL']
      if (dl == null) return 3
      def levels = [debug:0, info:1, warn:2, error:3]
      return levels.containsKey(dl.toLowerCase()) ? levels[dl.toLowerCase()] : dl.toInteger()
    } catch (Exception ignored) { return 3 }
  }
  def loadData(String filename, String stagename) { readYaml(filename)[stagename] }
  def setEnvFromFile(String filename) {
    def temp = readYaml(filename)
    if (temp.env != null) {
      for (each in temp.env.keySet()) { setEnvProperty(each, temp.env[each]) }
    }
  }
}

// ============================================================================
// 유틸리티 클래스 (@NonCPS 무상태 헬퍼)
// ============================================================================

/** 텍스트 내 ${env.VAR} 패턴을 환경 변수 값으로 치환. */
class EnvResolver implements Serializable {
  @NonCPS
  static List extractEnvKeys(String text) {
    def pattern = /\$\{\s*env\.([a-zA-Z_\.]*)\s*\}*/
    def keys = []
    def matcher = (text =~ pattern)
    for (int i = 0; i < matcher.count; i++) {
      keys.add([full: matcher[i][0], key: matcher[i][1]])
    }
    return keys
  }

  @NonCPS
  static String substitute(String text, List envEntries, Map envValues) {
    def result = text
    for (entry in envEntries) {
      def val = envValues[entry.key]
      if (val != null) result = result.replace(entry.full, val.toString())
    }
    return result
  }
}

/** 셸 명령어에 module load 접두사를 생성. */
class ModuleResolver implements Serializable {
  @NonCPS
  static String buildPrefix(List modules) {
    if (modules == null) return ""
    def joined = modules.join(" ")
    return "set +x; source \$MODULESHOME/init/zsh 2>/dev/null 1>/dev/null; module load ${joined}; set -x;"
  }
}

/** 고유하고 안전한 Jenkins 스테이지 이름을 생성. */
class StageNameBuilder implements Serializable {
  @NonCPS
  static String sanitize(String name) {
    return name.replaceAll(/[^A-Za-z0-9_.-]+/, '_')
  }

  @NonCPS
  static String forScalar(String templateName, String value) {
    return sanitize("${templateName}_${value}")
  }

  @NonCPS
  static String forMap(String templateName, Map entry, int index) {
    if (entry.containsKey('_name')) return sanitize("${templateName}_${entry._name}")
    return sanitize("${templateName}_${index}")
  }

  @NonCPS
  static String forTemplateRef(String templateName, Map bindings, List seenNames) {
    def baseName = sanitize(templateName)
    if (!seenNames.contains(baseName)) return baseName
    def suffixParts = []
    for (key in bindings.keySet().sort()) { suffixParts.add(bindings[key].toString()) }
    def candidate = sanitize("${templateName}_${suffixParts.join('_')}")
    if (!seenNames.contains(candidate)) return candidate
    int index = 2
    while (seenNames.contains(sanitize("${candidate}_${index}"))) { index++ }
    return sanitize("${candidate}_${index}")
  }
}

/**
 * {ARG_NAME} 재귀 치환 (깊이 제한 5).
 * 토큰이 남지 않거나 깊이 초과 시 중단.
 */
class TemplateResolver implements Serializable {
  @NonCPS
  static String substituteBindings(String text, Map bindings) {
    if (text == null) return null
    def result = text
    for (int depth = 0; depth < 5; depth++) {
      def prev = result
      for (entry in bindings.entrySet()) {
        result = result.replace("{${entry.key}}", entry.value.toString())
      }
      if (result == prev) return result
    }
    return result
  }

  @NonCPS
  static List resolveStepsWithBindings(List steps, Map bindings) {
    def resolved = []
    for (step in steps) {
      def resolvedStep = [:]
      for (entry in step.entrySet()) {
        if (entry.value instanceof String) {
          resolvedStep[entry.key] = substituteBindings(entry.value, bindings)
        } else {
          resolvedStep[entry.key] = entry.value
        }
      }
      resolved.add(resolvedStep)
    }
    return resolved
  }
}

/** over: 디렉티브를 반복 리스트로 확장. */
class IterationResolver implements Serializable {
  static List expandOver(def over, JenkinsContext jenkins, String stagename) {
    if (over instanceof List) return new ArrayList(over)
    if (over instanceof String && over.startsWith('env.')) {
      def varName = over.substring(4)
      def value = jenkins.getEnv(varName)
      if (value == null || value.trim() == '') return []
      try {
        def parsed = new JsonSlurper().parseText(value)
        if (parsed instanceof List) {
          def result = []
          for (item in parsed) {
            if (item instanceof Map) {
              def m = [:]
              for (e in item.entrySet()) { m[e.key] = e.value }
              result.add(m)
            } else {
              result.add(item.toString())
            }
          }
          return result
        }
      } catch (Exception ignored) {}
      def items = []
      for (part in value.split(',')) {
        def trimmed = part.trim()
        if (trimmed) items.add(trimmed)
      }
      return items
    }
    jenkins.error("iterated.over in stage '${stagename}' must be a list or 'env.VAR_NAME' string, got: ${over?.getClass()?.simpleName ?: 'null'}")
  }

  static Map buildBindingsFromItem(String templateName, List declaredArgs, def item, JenkinsContext jenkins, String stagename) {
    def bindings = [:]
    if (item instanceof String) {
      if (declaredArgs.size() != 1) {
        jenkins.error("Template '${templateName}': scalar iteration requires exactly one declared argument but template declares ${declaredArgs.size()} argument(s) (${declaredArgs.join(', ')}). Use a map list for multi-argument iteration.")
      }
      bindings[declaredArgs[0]] = item
    } else if (item instanceof Map) {
      for (entry in item.entrySet()) {
        if (entry.key != '_name') bindings[entry.key] = entry.value.toString()
      }
    } else {
      jenkins.error("Template '${templateName}': iterated item in stage '${stagename}' must be a scalar string or map of argument bindings, got ${item?.getClass()?.simpleName ?: 'null'}")
    }
    return bindings
  }
}

// ============================================================================
// 템플릿
// ============================================================================

/** 인자 선언과 검증을 포함하는 재사용 가능한 템플릿 스테이지. */
class Template implements Serializable {
  String name
  String filename
  Map data
  List arguments
  List steps

  Template(String name, String filename, Map data) {
    this.name = name
    this.filename = filename
    this.data = data
    this.arguments = data?.template?.arguments
    this.steps = data?.steps
  }

  def validate(JenkinsContext jenkins) {
    if (data == null) jenkins.error("Template '${name}' not found in '${filename}'")
    if (data.template == null) jenkins.error("Stage '${name}' is not a template - missing 'template:' key in '${filename}'")
    if (arguments == null || arguments.isEmpty()) jenkins.error("Template '${name}' must declare at least one argument under 'template.arguments'")
    if (steps == null) jenkins.error("Template '${name}' has no 'steps' section")
    if (data.stages != null || data.parallels != null) jenkins.error("Template '${name}' must contain only steps - found 'stages' or 'parallels' key (templates cannot contain structural elements)")
    for (step in steps) {
      if (step instanceof Map && (step.containsKey('template') || step.containsKey('iterated'))) {
        jenkins.error("Template '${name}' must contain only steps - found 'template' or 'iterated' key inside template steps")
      }
    }
  }

  def validateBindings(Map bindings, JenkinsContext jenkins, String usedInStage) {
    for (arg in arguments) {
      if (!bindings.containsKey(arg)) {
        jenkins.error("Template '${name}' requires argument '${arg}' but it was not supplied (used in stage '${usedInStage}')")
      }
    }
  }
}

// ============================================================================
// 스테이지 계층
// ============================================================================

/**
 * 모든 실행 가능한 스테이지의 추상 기반 클래스. CPS 호환을 위해 Serializable.
 * YAML 로딩, when: 평가, 부모 플래그 전파, 순환 참조 감지를 처리.
 */
abstract class Stage implements Serializable {
  String filename
  String stagename
  boolean flag = true
  Stage parent
  JenkinsContext jenkins
  Stage(String filename, String stagename, Stage parent, JenkinsContext jenkins) {
    this.filename = filename
    this.stagename = stagename
    this.parent = parent
    this.jenkins = jenkins
  }

  def load() { return jenkins.loadData(filename, stagename) }

  def checkCircularLoop(Stage other) {
    if (this.parent == null) return true
    if (this.parent.filename == other.filename && this.parent.stagename == other.stagename) {
      throw new Exception("Circular Loop Execution ${this.stagename} from ${other.filename}")
    }
    this.parent.checkCircularLoop(other)
  }

  /** 메인 진입점: 데이터 로드, when 평가, 서브클래스로 디스패치. */
  public def run() {
    def data = load()
    if (data != null && data.template != null) {
      jenkins.error("Template stage '${stagename}' cannot be executed directly via run() or use directive - instantiate it via 'template:' or 'iterated:' in a parallels/stages/steps block")
    }
    def whenData = data["when"]
    if (whenData == null) { whenData = true } else { whenData = jenkins.evaluate(whenData) }
    this.flag = whenData
    if (this.parent != null) this.flag = this.parent.flag && this.flag
    if (!this.flag) Utils.markStageSkippedForConditional(this.stagename)
    jenkins.info("Stage ENTER: ${stagename}")
    def result = execute(data)
    jenkins.info("Stage EXIT: ${stagename}")
    return result
  }

  /** 서브클래스별 실행 로직. */
  abstract def execute(Map data)

  /** StageFactory를 통해 자식 스테이지를 생성. */
  def constructChild(String file, String name) {
    def childData = jenkins.loadData(file, name)
    return StageFactory.create(file, name, this, jenkins, childData)
  }

  /** StepFactory를 사용하여 치환된 스텝 목록을 실행. */
  def executeResolvedSteps(List resolvedSteps, String moduleprefix) {
    for (step in resolvedSteps) {
      jenkins.debug("Step: ${step.keySet()}")
      StepFactory.create(step, jenkins, this, moduleprefix).run()
    }
  }
}

/** 하나의 Jenkins 스테이지 내에서 스텝 목록을 실행. env/node 래핑 처리. */
class StepsStage extends Stage {
  StepsStage(String filename, String stagename, Stage parent, JenkinsContext jenkins) {
    super(filename, stagename, parent, jenkins)
  }

  def execute(Map data) {
    def moduleprefix = ModuleResolver.buildPrefix(data["modules"])
    def resolvedSteps = resolveSteps(data)
    def content = { executeResolvedSteps(resolvedSteps, moduleprefix) }
    content = wrapWithEnv(content, data["env"])
    content = wrapWithNode(content, data["node"])
    if (this.flag) content()
  }

  private List resolveSteps(Map data) {
    def resolved = []
    for (each in data["steps"]) {
      if (each.containsKey("template")) {
        def templateName = each['template']
        def bindings = [:]
        for (entry in each.entrySet()) { if (entry.key != 'template') bindings[entry.key] = entry.value.toString() }
        def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
        tpl.validate(jenkins)
        tpl.validateBindings(bindings, jenkins, this.stagename)
        resolved.addAll(TemplateResolver.resolveStepsWithBindings(tpl.steps, bindings))
      } else if (each.containsKey("iterated")) {
        def iteratedData = each['iterated']
        def templateName = iteratedData['template']
        def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
        tpl.validate(jenkins)
        def items = IterationResolver.expandOver(iteratedData['over'], jenkins, this.stagename)
        for (int i = 0; i < items.size(); i++) {
          def bindings = IterationResolver.buildBindingsFromItem(templateName, tpl.arguments, items[i], jenkins, this.stagename)
          tpl.validateBindings(bindings, jenkins, this.stagename)
          resolved.addAll(TemplateResolver.resolveStepsWithBindings(tpl.steps, bindings))
        }
      } else {
        resolved.add(each)
      }
    }
    return resolved
  }

  private Closure wrapWithEnv(Closure content, def envData) {
    if (envData == null) return content
    def envDataList = []
    def cur = content
    for (each in envData.keySet()) { envDataList.add("${each}=${envData[each]}") }
    return { jenkins.withEnv(envDataList) { cur() } }
  }

  private Closure wrapWithNode(Closure content, def nodeData) {
    if (nodeData == null) return content
    def cur = content
    return { jenkins.node("${nodeData}") { cur() } }
  }
}

/** 자식 스테이지들을 순차적으로 실행. */
class SequentialStage extends Stage {
  SequentialStage(String filename, String stagename, Stage parent, JenkinsContext jenkins) {
    super(filename, stagename, parent, jenkins)
  }

  def execute(Map data) {
    this.checkCircularLoop(this)
    def temp = data['stages']
    def executionPlan = []
    def seenNames = []

    for (int i = 0; i < temp.size(); i++) {
      def each = temp[i]
      if (each instanceof String) {
        def parsed = parseStageRef(each)
        def capturedStage = parsed[0]
        def capturedFile = parsed[1]
        checkDuplicate(capturedStage, seenNames, "stages")
        seenNames.add(capturedStage)
        executionPlan.add({ def child = constructChild(capturedFile, capturedStage); jenkins.stage(child.stagename) { child.run() } })
      } else if (each instanceof Map && each.containsKey('template')) {
        def result = buildTemplateStage(each, seenNames, "stages")
        seenNames.add(result.name)
        def capturedName = result.name; def capturedSteps = result.steps
        executionPlan.add({ jenkins.stage(capturedName) { executeResolvedSteps(capturedSteps, "") } })
      } else if (each instanceof Map && each.containsKey('iterated')) {
        buildIteratedStages(each, seenNames, "stages").each { entry ->
          seenNames.add(entry.name)
          def capturedName = entry.name; def capturedSteps = entry.steps
          executionPlan.add({ jenkins.stage(capturedName) { executeResolvedSteps(capturedSteps, "") } })
        }
      }
    }
    for (execution in executionPlan) { execution() }
  }

  private List parseStageRef(String ref) {
    def nextFile = this.filename
    def nextStage = ref
    if (ref.contains("from")) {
      def splitted = ref.split("from")
      nextStage = splitted[0].trim()
      nextFile = splitted[1].trim()
    }
    return [nextStage, nextFile]
  }

  private void checkDuplicate(String name, List seenNames, String block) {
    if (seenNames.contains(name)) jenkins.error("Duplicate stage name '${name}' in ${block} block of '${this.stagename}'")
  }

  private Map buildTemplateStage(Map entry, List seenNames, String block) {
    def templateName = entry['template']
    def bindings = [:]
    for (e in entry.entrySet()) { if (e.key != 'template') bindings[e.key] = e.value.toString() }
    def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
    tpl.validate(jenkins)
    tpl.validateBindings(bindings, jenkins, this.stagename)
    def resolvedSteps = TemplateResolver.resolveStepsWithBindings(tpl.steps, bindings)
    def capturedName = StageNameBuilder.forTemplateRef(templateName, bindings, seenNames)
    checkDuplicate(capturedName, seenNames, block)
    return [name: capturedName, steps: resolvedSteps]
  }

  private List buildIteratedStages(Map entry, List seenNames, String block) {
    def iteratedData = entry['iterated']
    def templateName = iteratedData['template']
    def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
    tpl.validate(jenkins)
    def items = IterationResolver.expandOver(iteratedData['over'], jenkins, this.stagename)
    def results = []
    for (int j = 0; j < items.size(); j++) {
      def item = items[j]
      def stageName = (item instanceof String) ? StageNameBuilder.forScalar(templateName, item) : StageNameBuilder.forMap(templateName, item, j)
      def bindings = IterationResolver.buildBindingsFromItem(templateName, tpl.arguments, item, jenkins, this.stagename)
      tpl.validateBindings(bindings, jenkins, this.stagename)
      checkDuplicate(stageName, seenNames, block)
      results.add([name: stageName, steps: TemplateResolver.resolveStepsWithBindings(tpl.steps, bindings)])
    }
    return results
  }
}

/** Jenkins parallel 스텝을 통해 자식 스테이지들을 병렬 실행. */
class ParallelStage extends Stage {
  ParallelStage(String filename, String stagename, Stage parent, JenkinsContext jenkins) {
    super(filename, stagename, parent, jenkins)
  }

  def execute(Map data) {
    this.checkCircularLoop(this)
    def temp = data['parallels']
    def branches = [:]
    def seenNames = []

    for (int i = 0; i < temp.size(); i++) {
      def each = temp[i]
      if (each instanceof String) {
        def parsed = parseStageRef(each)
        def capturedStage = parsed[0]
        def capturedFile = parsed[1]
        checkDuplicate(capturedStage, seenNames, "parallels")
        seenNames.add(capturedStage)
        branches[capturedStage] = { jenkins.stage(capturedStage) { constructChild(capturedFile, capturedStage).run() } }
      } else if (each instanceof Map && each.containsKey('template')) {
        def result = buildTemplateStage(each, seenNames, "parallels")
        seenNames.add(result.name)
        def capturedName = result.name; def capturedSteps = result.steps
        branches[capturedName] = { jenkins.stage(capturedName) { executeResolvedSteps(capturedSteps, "") } }
      } else if (each instanceof Map && each.containsKey('iterated')) {
        buildIteratedStages(each, seenNames, "parallels").each { entry ->
          seenNames.add(entry.name)
          def capturedName = entry.name; def capturedSteps = entry.steps
          branches[capturedName] = { jenkins.stage(capturedName) { executeResolvedSteps(capturedSteps, "") } }
        }
      }
    }
    jenkins.parallel(branches)
  }

  private List parseStageRef(String ref) {
    def nextFile = this.filename
    def nextStage = ref
    if (ref.contains("from")) {
      def splitted = ref.split("from")
      nextStage = splitted[0].trim()
      nextFile = splitted[1].trim()
    }
    return [nextStage, nextFile]
  }

  private void checkDuplicate(String name, List seenNames, String block) {
    if (seenNames.contains(name)) jenkins.error("Duplicate stage name '${name}' in ${block} block of '${this.stagename}'")
  }

  private Map buildTemplateStage(Map entry, List seenNames, String block) {
    def templateName = entry['template']
    def bindings = [:]
    for (e in entry.entrySet()) { if (e.key != 'template') bindings[e.key] = e.value.toString() }
    def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
    tpl.validate(jenkins)
    tpl.validateBindings(bindings, jenkins, this.stagename)
    def resolvedSteps = TemplateResolver.resolveStepsWithBindings(tpl.steps, bindings)
    def capturedName = StageNameBuilder.forTemplateRef(templateName, bindings, seenNames)
    checkDuplicate(capturedName, seenNames, block)
    return [name: capturedName, steps: resolvedSteps]
  }

  private List buildIteratedStages(Map entry, List seenNames, String block) {
    def iteratedData = entry['iterated']
    def templateName = iteratedData['template']
    def tpl = new Template(templateName, this.filename, jenkins.loadData(this.filename, templateName))
    tpl.validate(jenkins)
    def items = IterationResolver.expandOver(iteratedData['over'], jenkins, this.stagename)
    def results = []
    for (int j = 0; j < items.size(); j++) {
      def item = items[j]
      def stageName = (item instanceof String) ? StageNameBuilder.forScalar(templateName, item) : StageNameBuilder.forMap(templateName, item, j)
      def bindings = IterationResolver.buildBindingsFromItem(templateName, tpl.arguments, item, jenkins, this.stagename)
      tpl.validateBindings(bindings, jenkins, this.stagename)
      checkDuplicate(stageName, seenNames, block)
      results.add([name: stageName, steps: TemplateResolver.resolveStepsWithBindings(tpl.steps, bindings)])
    }
    return results
  }
}

// ============================================================================
// 스텝 계층
// ============================================================================

/** 모든 실행 가능한 스텝의 추상 기반 클래스. CPS 호환을 위해 Serializable. */
abstract class Step implements Serializable {
  JenkinsContext jenkins
  Stage stage
  Map raw
  String moduleprefix

  Step(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) {
    this.raw = raw
    this.jenkins = jenkins
    this.stage = stage
    this.moduleprefix = moduleprefix
  }

  abstract def run()
}

/** 셸 명령어 실행. 환경 변수 치환 및 모듈 접두사 적용. */
class ShStep extends Step {
  ShStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() {
    def text = raw['sh']
    def envKeys = EnvResolver.extractEnvKeys(text)
    def envValues = [:]
    for (entry in envKeys) { envValues[entry.key] = jenkins.getEnv(entry.key) }
    def cmd = EnvResolver.substitute(text, envKeys, envValues)
    jenkins.sh("${moduleprefix}${cmd}")
  }
}

/** 외부 Groovy 스크립트를 로드하여 실행. */
class ScriptStep extends Step {
  ScriptStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() { jenkins.load(raw["script"]).main() }
}

/** YAML 파일에서 환경 변수를 로드. */
class SetEnvFromFileStep extends Step {
  SetEnvFromFileStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() { jenkins.setEnvFromFile(raw["setEnvFromFile"]) }
}

/** Groovy 표현식을 평가. */
class EvaluateStep extends Step {
  EvaluateStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() { jenkins.evaluate(raw["evaluate"]) }
}

/** 다른 스테이지를 참조하여 실행. makeStage 옵션으로 스테이지 래퍼 생성 여부 결정. */
class UseStageStep extends Step {
  UseStageStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() {
    def useValue = raw["use"]
    def makeStage = raw.containsKey("makeStage") ? raw["makeStage"] : true
    if (!useValue.contains(" from ")) jenkins.error("use directive must follow 'StageName from filepath' format: '${useValue}'")
    def parts = useValue.split(" from ", 2)
    def targetStage = parts[0].trim()
    def targetFile = parts[1].trim()
    def child = stage.constructChild(targetFile, targetStage)
    child.checkCircularLoop(child)
    if (makeStage) {
      jenkins.stage(targetStage) { child.run() }
    } else {
      child.run()
    }
  }
}

// ============================================================================
// 팩토리
// ============================================================================

/** YAML 구조에 따라 적절한 Stage 서브클래스를 생성. 알 수 없는 키는 즉시 실패. */
class StageFactory implements Serializable {
  static Stage create(String filename, String stagename, Stage parent, JenkinsContext jenkins, Map data) {
    if (data == null) jenkins.error("Stage '${stagename}' not found in '${filename}'")
    if (data.stages != null) return new SequentialStage(filename, stagename, parent, jenkins)
    if (data.parallels != null) return new ParallelStage(filename, stagename, parent, jenkins)
    if (data.steps != null) return new StepsStage(filename, stagename, parent, jenkins)
    if (data.template != null) jenkins.error("Template stage '${stagename}' cannot be executed directly - instantiate via 'template:' or 'iterated:' directive")
    jenkins.error("Stage '${stagename}' has no 'stages', 'parallels', or 'steps' key")
  }
}

/** 스텝 디렉티브 키에 따라 적절한 Step 서브클래스를 생성. 알 수 없는 키는 즉시 실패. */
class StepFactory implements Serializable {
  static Step create(Map step, JenkinsContext jenkins, Stage stage, String moduleprefix) {
    if (step.containsKey("sh")) return new ShStep(step, jenkins, stage, moduleprefix)
    if (step.containsKey("script")) return new ScriptStep(step, jenkins, stage, moduleprefix)
    if (step.containsKey("setEnvFromFile")) return new SetEnvFromFileStep(step, jenkins, stage, moduleprefix)
    if (step.containsKey("evaluate")) return new EvaluateStep(step, jenkins, stage, moduleprefix)
    if (step.containsKey("use")) return new UseStageStep(step, jenkins, stage, moduleprefix)
    jenkins.error("Unknown step directive '${step.keySet().first()}' in stage '${stage.stagename}'. Valid directives: sh, script, setEnvFromFile, evaluate, use")
  }
}

// ============================================================================
// 전역 함수 (공개 API — 시그니처 변경 없음)
// ============================================================================

def evaluation(value) {
  return evaluate(value)
}

def load_data(String filename, String stagename) {
  return readYaml(file: filename)[stagename]
}

def construct_stage(String filename, String stagename) {
  def jenkins = new JenkinsContext(this)
  def data = jenkins.loadData(filename, stagename)
  return StageFactory.create(filename, stagename, null, jenkins, data)
}

def setEnvFromFile(filename) {
  def temp = readYaml(file: filename)
  if (temp.env != null) {
    for (each in temp.env.keySet()) {
      env.setProperty(each, temp.env[each])
    }
  }
}

def run(filename, stagename) {
  def jenkins = new JenkinsContext(this)
  def data = jenkins.loadData(filename, stagename)
  def temp = StageFactory.create(filename, stagename, null, jenkins, data)
  stage(stagename) {
    temp.run()
  }
}
