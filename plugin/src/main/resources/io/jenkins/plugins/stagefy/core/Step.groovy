package io.jenkins.plugins.stagefy.core

abstract class Step implements Serializable {
    Map raw
    def jenkins
    Stage stage
    String moduleprefix

    Step(Map raw, def jenkins, Stage stage, String moduleprefix) {
        this.raw = raw
        this.jenkins = jenkins
        this.stage = stage
        this.moduleprefix = moduleprefix
    }

    abstract void run()
}
