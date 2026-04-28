package io.jenkins.plugins.stagefy.core

class ScriptStep extends Step {
    ScriptStep(Map raw, def jenkins, Stage stage, String moduleprefix) {
        super(raw, jenkins, stage, moduleprefix)
    }
    void run() { jenkins.load(raw.get("script")).main() }
}
