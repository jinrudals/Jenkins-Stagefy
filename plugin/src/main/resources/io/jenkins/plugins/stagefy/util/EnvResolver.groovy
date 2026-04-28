package io.jenkins.plugins.stagefy.util

class EnvResolver implements Serializable {
    static List extractEnvKeys(String text) {
        def pattern = /\$\{\s*env\.([a-zA-Z_.]+)\s*\}/
        def keys = []
        def matcher = (text =~ pattern)
        for (int i = 0; i < matcher.count; i++) {
            keys.add([full: matcher[i][0], key: matcher[i][1]])
        }
        return keys
    }

    static String substitute(String text, List envEntries, Map envValues) {
        def result = text
        for (entry in envEntries) {
            def val = envValues.get(entry.key)
            if (val != null) result = result.replace(entry.full, val.toString())
        }
        return result
    }
}
