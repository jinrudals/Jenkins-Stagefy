package io.jenkins.plugins.stagefy.core

class StepFactory {
    static Step create(Map step, def jenkins, Stage stage, String moduleprefix) {
        if (step.containsKey("sh")) return new ShStep(step, jenkins, stage, moduleprefix)
        if (step.containsKey("script")) return new ScriptStep(step, jenkins, stage, moduleprefix)
        if (step.containsKey("setEnvFromFile")) return new SetEnvFromFileStep(step, jenkins, stage, moduleprefix)
        if (step.containsKey("evaluate")) return new EvaluateStep(step, jenkins, stage, moduleprefix)
        if (step.containsKey("use")) return new UseStageStep(step, jenkins, stage, moduleprefix)
        jenkins.error("Unknown step directive '${step.keySet().first()}' in stage '${stage.stagename}'")
        return null
    }
}
