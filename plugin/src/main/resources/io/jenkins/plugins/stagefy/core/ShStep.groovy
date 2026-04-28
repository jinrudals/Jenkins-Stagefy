package io.jenkins.plugins.stagefy.core

import io.jenkins.plugins.stagefy.util.EnvResolver

class ShStep extends Step {
    ShStep(Map raw, def jenkins, Stage stage, String moduleprefix) {
        super(raw, jenkins, stage, moduleprefix)
    }

    void run() {
        String text = (String) raw.get("sh")
        def envKeys = EnvResolver.extractEnvKeys(text)
        Map envValues = [:]
        for (entry in envKeys) {
            envValues.put(entry.get("key"), jenkins.getEnv(entry.get("key")))
        }
        String cmd = EnvResolver.substitute(text, envKeys, envValues)
        jenkins.sh(moduleprefix + cmd)
    }
}
