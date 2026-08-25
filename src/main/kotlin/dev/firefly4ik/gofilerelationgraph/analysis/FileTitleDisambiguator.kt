package dev.firefly4ik.gofilerelationgraph.analysis

import java.nio.file.Path

internal object FileTitleDisambiguator {
    fun disambiguate(paths: List<String>, basePath: String?): Map<String, String> {
        val components = paths.map { path ->
            val relative = if (basePath == null) {
                path
            } else {
                runCatching { Path.of(basePath).relativize(Path.of(path)).toString() }.getOrDefault(path)
            }
            relative.replace('\\', '/').trimEnd('/').split('/').filter(String::isNotEmpty)
        }
        val suffixLengths = IntArray(paths.size) { 1 }

        while (true) {
            val duplicateGroups = paths.indices
                .groupBy { index -> components[index].takeLast(suffixLengths[index]).joinToString("/") }
                .values
                .filter { it.size > 1 }
            if (duplicateGroups.isEmpty()) break

            var expanded = false
            for (group in duplicateGroups) {
                for (index in group) {
                    if (suffixLengths[index] < components[index].size) {
                        suffixLengths[index]++
                        expanded = true
                    }
                }
            }
            if (!expanded) break
        }

        return paths.indices.associate { index ->
            paths[index] to components[index].takeLast(suffixLengths[index]).joinToString("/")
        }
    }
}
