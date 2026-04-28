package io.jenkins.plugins.stagefy

import org.jenkinsci.plugins.workflow.cps.CpsScript
import java.util.logging.Level
import java.util.logging.Logger

class DslJenkinsContext implements Serializable {
    private static final Logger LOGGER = Logger.getLogger(DslJenkinsContext.class.name)
    private def script

    DslJenkinsContext(CpsScript script) {
        this.script = script
    }

    def sh(String cmd) {
        script.sh(cmd)
    }

    def stage(String name, Closure body) {
        script.stage(name, body)
    }

    def parallel(Map branches) {
        script.parallel(branches)
    }

    def node(String label, Closure body) {
        script.node(label, body)
    }

    def withEnv(List envs, Closure body) {
        script.withEnv(envs, body)
    }

    def readYaml(String file) {
        return script.readYaml(file: file)
    }

    def load(String path) {
        return script.load(path)
    }

    def evaluate(value) {
        return script.evaluate(value)
    }

    def error(String msg) {
        LOGGER.log(Level.SEVERE, msg)
        script.error(msg)
    }

    def getEnv(String name) {
        return script.env[name]
    }

    def setEnvProperty(String key, Object value) {
        script.env.setProperty(key, value?.toString())
    }

    def log(String msg) {
        script.println(msg)
    }

    def debug(String msg) { LOGGER.log(Level.FINE, msg) }
    def info(String msg) { LOGGER.log(Level.INFO, msg) }
    def warn(String msg) { LOGGER.log(Level.WARNING, msg) }

    def loadData(String filename, String stagename) {
        return readYaml(filename)[stagename]
    }

    def setEnvFromFile(String filename) {
        def temp = readYaml(filename)
        if (temp.env != null) {
            temp.env.each { k, v -> setEnvProperty(k, v) }
        }
    }
}
