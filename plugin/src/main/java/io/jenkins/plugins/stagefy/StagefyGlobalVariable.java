package io.jenkins.plugins.stagefy;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Binding;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.cps.GroovySourceFileAllowlist;

@Extension
public class StagefyGlobalVariable extends GlobalVariable {
    @NonNull
    @Override
    public String getName() {
        return "stagefy";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) throws Exception {
        Binding binding = script.getBinding();
        Object stagefy;
        if (binding.hasVariable(getName())) {
            stagefy = binding.getVariable(getName());
        } else {
            stagefy = script.getClass().getClassLoader()
                    .loadClass("io.jenkins.plugins.stagefy.StagefyDsl")
                    .getConstructor(CpsScript.class)
                    .newInstance(script);
            binding.setVariable(getName(), stagefy);
        }
        return stagefy;
    }

    @Extension
    public static class Allowlist extends GroovySourceFileAllowlist {
        private static final String BASE = "jar:file:";
        private static final String PLUGIN_PATH = "/stagefy/WEB-INF/lib/stagefy.jar!/io/jenkins/plugins/stagefy/";

        @Override
        public boolean isAllowed(String groovyResourceUrl) {
            return groovyResourceUrl.contains("/io/jenkins/plugins/stagefy/")
                    && groovyResourceUrl.endsWith(".groovy");
        }
    }
}
