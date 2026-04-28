package io.jenkins.plugins.stagefy.core

class SetEnvFromFileStep extends Step {
    SetEnvFromFileStep(Map raw, def jenkins, Stage stage, String moduleprefix) {
        super(raw, jenkins, stage, moduleprefix)
    }
    void run() { jenkins.setEnvFromFile((String) raw.get("setEnvFromFile")) }
}
