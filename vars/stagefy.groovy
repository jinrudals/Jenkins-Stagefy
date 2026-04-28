/*
Load Jenkins stages from YAML.
OOP refactored architecture:
  - Stage layer: StepsStage, SequentialStage, ParallelStage
  - Step layer: ShStep, ScriptStep, UseStageStep, TemplateStep, IteratedStep, etc.
  - Template system with recursive variable substitution (depth limit 5)
  - JenkinsContext adapter for all DSL calls
  - DEBUG_LEVEL env var for execution tracing (0=silent, 1=stage, 2=step)
*/
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import groovy.json.JsonSlurper

// ============================================================================
// Jenkins Adapter
// ============================================================================

/**
 * Wraps all Jenkins pipeline DSL calls. Enables testing and abstraction.
 * Serializable for CPS compatibility — stored in Stage/Step fields across closures.
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
// Utility Classes (@NonCPS stateless helpers)
// ============================================================================

/** Resolves ${env.VAR} patterns in text. */
class EnvResolver implements Serializable {
  @NonCPS
  static String resolve(String text, def jenkins) {
    def pattern = /\$\{\s*env\.[a-zA-Z_\.]*\s*\}*/
    def envList = [:]
    for (eachEnv in text.findAll(pattern)) {
      def temp = eachEnv.split("env.")[-1].replace("}", "").trim()
      envList[eachEnv] = jenkins.getEnv(temp)
    }
    def result = text
    for (eachKey in envList.keySet()) {
      result = result.replace(eachKey, envList[eachKey])
    }
    return result
  }
}

/** Builds module load prefix for shell commands. */
class ModuleResolver implements Serializable {
  @NonCPS
  static String buildPrefix(List modules) {
    if (modules == null) return ""
    def joined = modules.join(" ")
    return "set +x; source \$MODULESHOME/init/zsh 2>/dev/null 1>/dev/null; module load ${joined}; set -x;"
  }
}

/** Generates unique, sanitized Jenkins stage names. */
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
 * Recursive {ARG_NAME} substitution with depth limit of 5.
 * Loops until no tokens remain or depth exhausted.
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

/** Expands over: directives into iteration lists. */
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
// Template
// ============================================================================

/** Represents a reusable template stage with argument declarations and validation. */
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
// Stage Layer
// ============================================================================

/**
 * Abstract base for all executable stages. Serializable for CPS.
 * Handles: YAML loading, when: evaluation, parent flag propagation, circular loop detection.
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

  /** Main entry: load data, evaluate when, dispatch to subclass. */
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

  /** Subclass-specific execution. */
  abstract def execute(Map data)

  /** Create a child stage from YAML data via StageFactory. */
  def constructChild(String file, String name) {
    def childData = jenkins.loadData(file, name)
    return StageFactory.create(file, name, this, jenkins, childData)
  }

  /** Execute resolved steps using StepFactory. */
  def executeResolvedSteps(List resolvedSteps, String moduleprefix) {
    for (step in resolvedSteps) {
      jenkins.debug("Step: ${step.keySet()}")
      StepFactory.create(step, jenkins, this, moduleprefix).run()
    }
  }
}

/** Executes a list of steps within one Jenkins stage. Handles env/node wrapping. */
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

/** Orchestrates child stages sequentially. */
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

/** Orchestrates child stages in parallel via Jenkins parallel step. */
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
// Step Layer
// ============================================================================

/** Abstract base for all executable steps. Serializable for CPS. */
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

/** Executes shell commands with env var and module prefix substitution. */
class ShStep extends Step {
  ShStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() {
    def cmd = EnvResolver.resolve(raw['sh'], jenkins)
    jenkins.sh("${moduleprefix}${cmd}")
  }
}

/** Loads and executes external Groovy scripts. */
class ScriptStep extends Step {
  ScriptStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() { jenkins.load(raw["script"]).main() }
}

/** Loads environment variables from a YAML file. */
class SetEnvFromFileStep extends Step {
  SetEnvFromFileStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() { jenkins.setEnvFromFile(raw["setEnvFromFile"]) }
}

/** Evaluates Groovy expressions. */
class EvaluateStep extends Step {
  EvaluateStep(Map raw, JenkinsContext jenkins, Stage stage, String moduleprefix) { super(raw, jenkins, stage, moduleprefix) }
  def run() { jenkins.evaluate(raw["evaluate"]) }
}

/** References and executes another stage, optionally creating a stage wrapper. */
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
// Factories
// ============================================================================

/** Creates appropriate Stage subclass based on YAML structure. Fail-fast on unknown keys. */
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

/** Creates appropriate Step subclass based on step directive key. Fail-fast on unknown keys. */
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
// Global Functions (public API — signatures unchanged)
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
