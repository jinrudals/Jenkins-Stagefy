package io.jenkins.plugins.stagefy

import org.jenkinsci.plugins.workflow.cps.CpsScript

class StagefyDsl implements Serializable {
    private CpsScript script

    StagefyDsl(CpsScript script) {
        this.script = script
    }

    def run(String file, String stage) {
        def jenkins = new DslJenkinsContext(script)
        def data = jenkins.loadData(file, stage)
        def stageObj = io.jenkins.plugins.stagefy.core.StageFactory.create(file, stage, null, jenkins, data)
        script.stage(stage) {
            stageObj.run()
        }
    }
}
