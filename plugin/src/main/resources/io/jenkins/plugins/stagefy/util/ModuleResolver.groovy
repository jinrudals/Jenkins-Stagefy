package io.jenkins.plugins.stagefy.util

class ModuleResolver implements Serializable {
    static String buildPrefix(List modules) {
        if (modules == null || modules.isEmpty()) return ""
        def joined = modules.join(" ")
        return "set +x; source \$MODULESHOME/init/zsh 2>/dev/null 1>/dev/null; module load ${joined}; set -x;"
    }
}
