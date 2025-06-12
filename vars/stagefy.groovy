/*
 * Groovy Library for loading Jenkins stages from YAML.
 * Supports:
 *  - Loading stages from external YAML files
 *  - Dynamic stage composition (parallel, sequential, single)
 *  - Stage skipping logic via "when"
 *  - Environment and node scoping
 */

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import groovy.transform.InheritConstructors
import groovy.json.JsonSlurper

class Stagefy {
  String filename
  String stagename
  Object script
  boolean flag = true
  def parent

  Stagefy(String filename, String stagename, parent, script) {
    this.filename = filename
    this.stagename = stagename
    this.parent = parent
    this.script = script
  }

  def load_data(String filename, String stagename) {
    return this.script.load_data(filename, stagename)
  }

  def steps_run() {
    def data = load_data(filename, stagename)
    def envData = data["env"]
    def nodeData = data["node"]
    def steps = data["steps"] ?: []

    def content = {
      for (step in steps) {
        if (step.sh) {
          def shell = step.sh
          shell = shell.replaceAll(/\$\{\s*env\.([a-zA-Z0-9_\.]+)\s*\}/) { _, var ->
            return this.script.env.getProperty(var)
          }
          this.script.sh(shell)

        } else if (step.script) {
          this.script.load(step.script).main()

        } else if (step.setEnvFromFile) {
          this.script.setEnvFromFile(step.setEnvFromFile)

        } else if (step.evaluate) {
          this.script.evaluation(step.evaluate)
        }
      }
    }

    def wrapped = content
    if (envData) {
      wrapped = {
        this.script.withEnv(envData.collect { k, v -> "${k}=${v}" }) {
          content()
        }
      }
    }

    if (nodeData) {
      def current = wrapped
      wrapped = {
        this.script.node(nodeData) {
          current()
        }
      }
    }

    if (flag) wrapped()
  }

  def parallels_run() {
    def parallels = load_data(filename, stagename)["parallels"] ?: []
    def branches = [:]

    for (entry in parallels) {
      def nextStage = entry
      def nextFile = filename

      if (entry.contains("from")) {
        def (stageName, fileName) = entry.split("from").collect { it.trim() }
        nextStage = stageName
        nextFile = fileName
      }

      branches[nextStage] = {
        this.script.stage(nextStage) {
          construct_stage(nextFile, nextStage).run()
        }
      }
    }
    this.script.parallel branches
  }

  def stages_run() {
    def stages = load_data(filename, stagename)["stages"] ?: []
    for (entry in stages) {
      def nextStage = entry
      def nextFile = filename

      if (entry.contains("from")) {
        def (stageName, fileName) = entry.split("from").collect { it.trim() }
        nextStage = stageName
        nextFile = fileName
      }

      def child = construct_stage(nextFile, nextStage)
      this.script.stage(nextStage) {
        child.run()
      }
    }
  }

  def construct_stage(String filename, String stagename) {
    return this.getClass().newInstance(filename, stagename, this, script)
  }

  def check_circular_loop(Stagefy other) {
    if (parent == null) return
    if (parent.filename == other.filename && parent.stagename == other.stagename) {
      throw new IllegalStateException("Circular Loop Detected: ${stagename} from ${filename}")
    }
    parent.check_circular_loop(other)
  }

  def run() {
    def data = load_data(filename, stagename)
    def whenCondition = data["when"]

    flag = whenCondition == null ? true : script.evaluation(whenCondition)
    if (parent) flag = parent.flag && flag

    if (!flag) {
      Utils.markStageSkippedForConditional(stagename)
      return
    }

    check_circular_loop(this)

    if (data.stages)      return stages_run()
    if (data.parallels)   return parallels_run()
    if (data.steps)       return steps_run()

    script.error("Stage '${stagename}' has no valid execution type (stages/steps/parallels)")
  }
}

// Helper functions for Jenkins global usage

def evaluation(String expr) {
  return evaluate(expr)
}

def load_data(String file, String stage) {
  return readYaml(file: file)[stage]
}

def setEnvFromFile(String file) {
  def envs = readYaml(file: file)["env"]
  if (envs) {
    envs.each { k, v -> env.setProperty(k, v) }
  }
}

def construct_stage(String file, String stage) {
  return new Stagefy(file, stage, null, this)
}

def run(String file, String stage) {
  def instance = construct_stage(file, stage)
  stage(stage) {
    instance.run()
  }
}
