package io.jenkins.plugins.stagefy.core

class EvaluateStep extends Step {
    EvaluateStep(Map raw, def jenkins, Stage stage, String moduleprefix) {
        super(raw, jenkins, stage, moduleprefix)
    }
    void run() { jenkins.evaluate(raw.get("evaluate")) }
}
