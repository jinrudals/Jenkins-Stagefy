/*
Load Jenkins stages from YAML.
Need to support followings
  - load stage from other file
  - load stage dynamically
  - template stages with variable substitution
  - iterated parallel/sequential/inline stage generation
*/
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

import groovy.transform.InheritConstructors
import groovy.json.JsonSlurper

class Stagefy {
  String filename
  String stagename
  boolean flag
  def parent

  Stagefy(filename, stagename, parent){
    this.filename = filename
    this.stagename = stagename
    this.flag   = true
    this.parent = parent
  }

  def load_data(String filename, String stagename){
    return this.script.load_data(filename, stagename)
  }

  // Template helpers

  // T002: Replace {ARG_NAME} placeholders in text with values from bindings
  @NonCPS
  def substituteBindings(String text, Map bindings) {
    if (text == null) return null
    def result = text
    for (entry in bindings.entrySet()) {
      result = result.replace("{${entry.key}}", entry.value.toString())
    }
    return result
  }

  // T003: Return a new step list with all string values substituted via bindings
  @NonCPS
  def resolveStepsWithBindings(List steps, Map bindings) {
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

  // T004: Validate that data is a well-formed template definition
  def validateTemplateData(String templateName, Map data) {
    if (data == null) {
      this.script.error("Template '${templateName}' not found in '${this.filename}'")
    }
    if (data.template == null) {
      this.script.error("Stage '${templateName}' is not a template - missing 'template:' key in '${this.filename}'")
    }
    if (data.template.arguments == null || data.template.arguments.isEmpty()) {
      this.script.error("Template '${templateName}' must declare at least one argument under 'template.arguments'")
    }
    if (data.steps == null) {
      this.script.error("Template '${templateName}' has no 'steps' section")
    }
    if (data.stages != null || data.parallels != null) {
      this.script.error("Template '${templateName}' must contain only steps - found 'stages' or 'parallels' key (templates cannot contain structural elements)")
    }
    for (step in data.steps) {
      if (step instanceof Map && (step.containsKey('template') || step.containsKey('iterated'))) {
        this.script.error("Template '${templateName}' must contain only steps - found 'template' or 'iterated' key inside template steps")
      }
    }
  }

  // T005: Assert all declaredArgs are present in bindings
  def validateBindings(String templateName, List declaredArgs, Map bindings) {
    for (arg in declaredArgs) {
      if (!bindings.containsKey(arg)) {
        this.script.error("Template '${templateName}' requires argument '${arg}' but it was not supplied (used in stage '${this.stagename}')")
      }
    }
  }

  // T012: Stage name for scalar iteration
  @NonCPS
  def buildStageNameForScalar(String templateName, String value) {
    return sanitizeStageName("${templateName}_${value}")
  }

  // T019: Stage name for map iteration
  @NonCPS
  def buildStageNameForMap(String templateName, Map entry, int index) {
    if (entry.containsKey('_name')) {
      return sanitizeStageName("${templateName}_${entry._name}")
    }
    return sanitizeStageName("${templateName}_${index}")
  }

  // Direct template refs use the template name unless that name already exists.
  // On conflict, derive a stable suffix from binding values, then fall back to an index.
  @NonCPS
  def buildStageNameForTemplateRef(String templateName, Map bindings, List seenNames) {
    def baseName = sanitizeStageName(templateName)
    if (!seenNames.contains(baseName)) {
      return baseName
    }
    def suffixParts = []
    for (key in bindings.keySet().sort()) {
      suffixParts.add(bindings[key].toString())
    }
    def candidate = sanitizeStageName("${templateName}_${suffixParts.join('_')}")
    if (!seenNames.contains(candidate)) {
      return candidate
    }
    int index = 2
    while (seenNames.contains(sanitizeStageName("${candidate}_${index}"))) {
      index++
    }
    return sanitizeStageName("${candidate}_${index}")
  }

  // T027: Replace characters invalid in Jenkins stage names with underscores
  @NonCPS
  def sanitizeStageName(String name) {
    return name.replaceAll(/[^A-Za-z0-9_.-]+/, '_')
  }

  // Build the bindings map from a single iterated item (string or map)
  def buildBindingsFromItem(String templateName, List declaredArgs, def item) {
    def bindings = [:]
    if (item instanceof String) {
      if (declaredArgs.size() != 1) {
        this.script.error("Template '${templateName}': scalar iteration requires exactly one declared argument but template declares ${declaredArgs.size()} argument(s) (${declaredArgs.join(', ')}). Use a map list for multi-argument iteration.")
      }
      bindings[declaredArgs[0]] = item
    } else if (item instanceof Map) {
      for (entry in item.entrySet()) {
        if (entry.key != '_name') {
          bindings[entry.key] = entry.value.toString()
        }
      }
    } else {
      this.script.error("Template '${templateName}': iterated item in stage '${this.stagename}' must be a scalar string or map of argument bindings, got ${item?.getClass()?.simpleName ?: 'null'}")
    }
    return bindings
  }

  // T013 + T023: Resolve over: value to a list (strings or maps)
  // Handles: inline list, env.VAR_NAME (JSON auto-detect then comma-split fallback)
  def expandIteratedOver(def over) {
    if (over instanceof List) {
      return new ArrayList(over)
    }
    if (over instanceof String && over.startsWith('env.')) {
      def varName = over.substring(4)
      def value = this.script.env[varName]
      if (value == null || value.trim() == '') {
        return []
      }
      // T023: try JSON parse first (supports map list for multi-arg templates)
      try {
        def parsed = new JsonSlurper().parseText(value)
        if (parsed instanceof List) {
          def result = []
          for (item in parsed) {
            if (item instanceof Map) {
              // convert to plain LinkedHashMap for CPS serialization safety
              def m = [:]
              for (e in item.entrySet()) { m[e.key] = e.value }
              result.add(m)
            } else {
              result.add(item.toString())
            }
          }
          return result
        }
      } catch (Exception ignored) {
        // not valid JSON - fall through to comma-split
      }
      // comma-split fallback (scalar list)
      def items = []
      for (part in value.split(',')) {
        def trimmed = part.trim()
        if (trimmed) items.add(trimmed)
      }
      return items
    }
    this.script.error("iterated.over in stage '${this.stagename}' must be a list or 'env.VAR_NAME' string, got: ${over?.getClass()?.simpleName ?: 'null'}")
  }

  // Step execution helper

  // Execute a single resolved step map (shared by steps_run, parallels_run, stages_run)
  def executeStep(def step, String moduleprefix, def envPattern) {
    if (step.containsKey("sh")) {
      def s_shell = step['sh']
      def envList = [:]
      for (eachEnv in s_shell.findAll(envPattern)) {
        def temp = eachEnv.split("env.")[-1].replace("}", "").trim()
        envList[eachEnv] = this.script.env[temp]
      }
      for (eachKey in envList.keySet()) {
        s_shell = s_shell.replace(eachKey, envList[eachKey])
      }
      this.script.sh("${moduleprefix}${s_shell}")
    } else if (step.containsKey("script")) {
      this.script.load(step["script"]).main()
    } else if (step.containsKey("setEnvFromFile")) {
      this.script.setEnvFromFile(step["setEnvFromFile"])
    } else if (step.containsKey("evaluate")) {
      this.script.evaluation(step["evaluate"])
    } else if (step.containsKey("use")) {
      def useValue = step["use"]
      def makeStage = step.containsKey("makeStage") ? step["makeStage"] : true
      if (!useValue.contains(" from ")) {
        this.script.error("use directive must follow 'StageName from filepath' format: '${useValue}'")
      }
      def parts = useValue.split(" from ", 2)
      def targetStage = parts[0].trim()
      def targetFile = parts[1].trim()
      def childStage = this.construct_stage(targetFile, targetStage)
      childStage.check_circular_loop(childStage)
      if (makeStage) {
        this.script.stage(targetStage) {
          childStage.run()
        }
      } else {
        childStage.run()
      }
    }
  }

  // Stage runners

  // T007: steps_run extended with template: and iterated: step support
  public def steps_run() {
    def data = load_data(this.filename, this.stagename)
    def pattern = /\$\{\s*env\.[a-z|A-Z|_|\.]*\s*\}*/
    def envData = data["env"]
    def nodeData = data["node"]
    def contents = []
    def moduleprefix = ""
    if (data["modules"] != null) {
      def joined = data["modules"].join(" ")
      moduleprefix = "set +x; source \$MODULESHOME/init/zsh 2>/dev/null 1>/dev/null; module load ${joined}; set -x;"
    }
    def resolvedExecutionSteps = []
    for (each in data["steps"]) {
      if (each.containsKey("template")) {
        // T007: direct template reference - inline steps into current stage
        def templateName = each['template']
        def bindings = [:]
        for (entry in each.entrySet()) {
          if (entry.key != 'template') bindings[entry.key] = entry.value.toString()
        }
        def templateData = load_data(this.filename, templateName)
        validateTemplateData(templateName, templateData)
        validateBindings(templateName, templateData.template.arguments, bindings)
        resolvedExecutionSteps.addAll(resolveStepsWithBindings(templateData.steps, bindings))
      } else if (each.containsKey("iterated")) {
        // T016: iterated template reference - inline N copies of resolved steps
        def iteratedData = each['iterated']
        def templateName = iteratedData['template']
        def templateData = load_data(this.filename, templateName)
        validateTemplateData(templateName, templateData)
        def items = expandIteratedOver(iteratedData['over'])
        for (int i = 0; i < items.size(); i++) {
          def item = items[i]
          def bindings = buildBindingsFromItem(templateName, templateData.template.arguments, item)
          validateBindings(templateName, templateData.template.arguments, bindings)
          resolvedExecutionSteps.addAll(resolveStepsWithBindings(templateData.steps, bindings))
        }
      } else {
        resolvedExecutionSteps.add(each)
      }
    }

    def content = {
      for (each in resolvedExecutionSteps) {
        executeStep(each, moduleprefix, pattern)
      }
    }
    contents.add(content)

    if (envData != null) {
      def envDataList = []
      def currentContent = contents[-1]
      for (each in envData.keySet()) {
        envDataList.add("${each}=${envData[each]}")
      }
      contents.add({
        this.script.withEnv(envDataList) {
          currentContent()
        }
      })
    }

    if (nodeData != null) {
      def currentContent = contents[-1]
      contents.add({
        this.script.node("${nodeData}") {
          currentContent()
        }
      })
    }
    if (this.flag) {
      contents[-1]()
    }
  }

  // T008 + T014 + T020: parallels_run extended with template: and iterated: support
  public def parallels_run() {
    def temp = load_data(this.filename, this.stagename)['parallels']
    def data = [:]
    def seenNames = []   // T026: duplicate stage name detection
    def pattern = /\$\{\s*env\.[a-z|A-Z|_|\.]*\s*\}*/

    for (int i = 0; i < temp.size(); i++) {
      def each = temp[i]

      if (each instanceof String) {
        // existing string-entry behavior
        def nextFile = "\$HERE"
        def nextStage = each
        if (each.contains("from")) {
          def splitted = each.split("from")
          nextStage = splitted[0].trim()
          nextFile = splitted[1].trim()
        }
        if (nextFile == "\$HERE") nextFile = this.filename
        def capturedStage = nextStage
        def capturedFile = nextFile
        if (seenNames.contains(capturedStage)) {
          this.script.error("Duplicate stage name '${capturedStage}' in parallels block of '${this.stagename}'")
        }
        seenNames.add(capturedStage)
        data[capturedStage] = {
          this.script.stage(capturedStage) {
            this.construct_stage(capturedFile, capturedStage).run()
          }
        }

      } else if (each instanceof Map && each.containsKey('template')) {
        // T008: direct template reference - one new parallel stage
        def templateName = each['template']
        def bindings = [:]
        for (entry in each.entrySet()) {
          if (entry.key != 'template') bindings[entry.key] = entry.value.toString()
        }
        def templateData = load_data(this.filename, templateName)
        validateTemplateData(templateName, templateData)
        validateBindings(templateName, templateData.template.arguments, bindings)
        def resolvedSteps = resolveStepsWithBindings(templateData.steps, bindings)
        def capturedName = buildStageNameForTemplateRef(templateName, bindings, seenNames)
        def capturedSteps = resolvedSteps
        if (seenNames.contains(capturedName)) {
          this.script.error("Duplicate stage name '${capturedName}' in parallels block of '${this.stagename}'")
        }
        seenNames.add(capturedName)
        data[capturedName] = {
          this.script.stage(capturedName) {
            for (step in capturedSteps) {
              executeStep(step, "", pattern)
            }
          }
        }

      } else if (each instanceof Map && each.containsKey('iterated')) {
        // T014 + T020: iterated template - N new parallel stages
        def iteratedData = each['iterated']
        def templateName = iteratedData['template']
        def templateData = load_data(this.filename, templateName)
        validateTemplateData(templateName, templateData)
        def items = expandIteratedOver(iteratedData['over'])
        for (int j = 0; j < items.size(); j++) {
          def item = items[j]
          def stageName = (item instanceof String) ?
            buildStageNameForScalar(templateName, item) :
            buildStageNameForMap(templateName, item, j)
          def bindings = buildBindingsFromItem(templateName, templateData.template.arguments, item)
          validateBindings(templateName, templateData.template.arguments, bindings)
          def resolvedSteps = resolveStepsWithBindings(templateData.steps, bindings)
          def capturedName = stageName
          def capturedSteps = resolvedSteps
          if (seenNames.contains(capturedName)) {
            this.script.error("Duplicate stage name '${capturedName}' in parallels block of '${this.stagename}'")
          }
          seenNames.add(capturedName)
          data[capturedName] = {
            this.script.stage(capturedName) {
              for (step in capturedSteps) {
                executeStep(step, "", pattern)
              }
            }
          }
        }
      }
    }
    this.script.parallel data
  }

  // T009 + T015 + T020: stages_run extended with template: and iterated: support
  public def stages_run() {
    def temp = this.script.load_data(this.filename, this.stagename)['stages']
    def pattern = /\$\{\s*env\.[a-z|A-Z|_|\.]*\s*\}*/
    def executionPlan = []
    def seenNames = []

    for (int i = 0; i < temp.size(); i++) {
      def each = temp[i]

      if (each instanceof String) {
        // existing string-entry behavior
        def nextFile = "\$HERE"
        def nextStage = each
        if (each.contains("from")) {
          def splitted = each.split("from")
          nextStage = splitted[0].trim()
          nextFile = splitted[1].trim()
        }
        if (nextFile == "\$HERE") nextFile = this.filename
        def capturedStage = nextStage
        def capturedFile = nextFile
        if (seenNames.contains(capturedStage)) {
          this.script.error("Duplicate stage name '${capturedStage}' in stages block of '${this.stagename}'")
        }
        seenNames.add(capturedStage)
        executionPlan.add({
          def childStage = this.construct_stage(capturedFile, capturedStage)
          this.script.stage(childStage.stagename) {
            childStage.run()
          }
        })

      } else if (each instanceof Map && each.containsKey('template')) {
        // T009: direct template reference - one new sequential stage
        def templateName = each['template']
        def bindings = [:]
        for (entry in each.entrySet()) {
          if (entry.key != 'template') bindings[entry.key] = entry.value.toString()
        }
        def templateData = load_data(this.filename, templateName)
        validateTemplateData(templateName, templateData)
        validateBindings(templateName, templateData.template.arguments, bindings)
        def resolvedSteps = resolveStepsWithBindings(templateData.steps, bindings)
        def capturedName = buildStageNameForTemplateRef(templateName, bindings, seenNames)
        def capturedSteps = resolvedSteps
        if (seenNames.contains(capturedName)) {
          this.script.error("Duplicate stage name '${capturedName}' in stages block of '${this.stagename}'")
        }
        seenNames.add(capturedName)
        executionPlan.add({
          this.script.stage(capturedName) {
            for (step in capturedSteps) {
              executeStep(step, "", pattern)
            }
          }
        })

      } else if (each instanceof Map && each.containsKey('iterated')) {
        // T015 + T020: iterated template - N sequential stages in list order
        def iteratedData = each['iterated']
        def templateName = iteratedData['template']
        def templateData = load_data(this.filename, templateName)
        validateTemplateData(templateName, templateData)
        def items = expandIteratedOver(iteratedData['over'])
        for (int j = 0; j < items.size(); j++) {
          def item = items[j]
          def stageName = (item instanceof String) ?
            buildStageNameForScalar(templateName, item) :
            buildStageNameForMap(templateName, item, j)
          def bindings = buildBindingsFromItem(templateName, templateData.template.arguments, item)
          validateBindings(templateName, templateData.template.arguments, bindings)
          def resolvedSteps = resolveStepsWithBindings(templateData.steps, bindings)
          def capturedName = stageName
          def capturedSteps = resolvedSteps
          if (seenNames.contains(capturedName)) {
            this.script.error("Duplicate stage name '${capturedName}' in stages block of '${this.stagename}'")
          }
          seenNames.add(capturedName)
          executionPlan.add({
            this.script.stage(capturedName) {
              for (step in capturedSteps) {
                executeStep(step, "", pattern)
              }
            }
          })
        }
      }
    }

    for (execution in executionPlan) {
      execution()
    }
  }

  def construct_stage(String filename, String stagename) {
    return this.getClass().newInstance(filename, stagename, this)
  }

  def check_circular_loop(other){
    if (this.parent == null){
      return true
    } else {
      if (this.parent.filename == other.filename && this.parent.stagename == other.stagename){
        throw new Exception("Circular Loop Execution ${this.stagename} from ${other.filename}")
      } else {
        this.parent.check_circular_loop(other)
      }
    }
  }

  // T006: run() with template guard before existing dispatch
  public def run(){
    def temp = this.load_data(this.filename, this.stagename)
    if (temp != null && temp.template != null) {
      this.script.error("Template stage '${this.stagename}' cannot be executed directly via run() or use directive - instantiate it via 'template:' or 'iterated:' in a parallels/stages/steps block")
    }
    def whenData = temp["when"]
    if (whenData == null){
      whenData = true
    } else {
      whenData = this.script.evaluation(whenData)
    }
    this.flag = whenData
    if (this.parent != null){
      this.flag = this.parent.flag && this.flag
    }
    if (!this.flag){
      Utils.markStageSkippedForConditional(this.stagename)
    }
    if (temp.stages != null){
      this.check_circular_loop(this)
      return this.stages_run()
    } else if (temp.parallels != null){
      this.check_circular_loop(this)
      return this.parallels_run()
    } else if (temp.steps != null){
      return this.steps_run()
    } else {
      this.script.error("Stage '${this.stagename}' has no 'stages', 'parallels', or 'steps' key")
    }
  }
}



// wrapper for evaluation
def evaluation(value){
  return evaluate(value)
}
def load_data(String filename, String stagename){
  return readYaml(file : filename)[stagename]
}

def construct_stage(String filename, String stagename){
  return new Stagefy(filename, stagename, null)
}

def setEnvFromFile(filename){
  def temp = readYaml(file : filename)
  if(temp.env != null){
    for (each in temp.env.keySet()){
      env.setProperty(each, temp.env[each])
    }
  }
}
def run(filename, stagename){
  Stagefy.metaClass.script = this
  def temp = construct_stage(filename, stagename)
  stage(stagename){
    temp.run()
  }
}
