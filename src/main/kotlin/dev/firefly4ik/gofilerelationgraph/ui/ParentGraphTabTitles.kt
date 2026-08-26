package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import dev.firefly4ik.gofilerelationgraph.analysis.FileTitleDisambiguator

object ParentGraphTabTitles {
    private val INFO_KEY = Key.create<TabInfo>("GoFileRelationGraph.ParentTabInfo")

    fun register(content: Content, symbolName: String, filePath: String) {
        content.putUserData(INFO_KEY, TabInfo(symbolName, filePath))
    }

    fun update(contentManager: ContentManager, basePath: String?) {
        val parentContents = contentManager.contents.mapNotNull { content ->
            content.getUserData(INFO_KEY)?.let { info -> content to info }
        }
        for ((symbolName, entries) in parentContents.groupBy { (_, info) -> info.symbolName }) {
            val distinctPaths = entries.map { (_, info) -> info.filePath }.distinct()
            val fileTitles = if (distinctPaths.size > 1) {
                FileTitleDisambiguator.disambiguate(distinctPaths, basePath)
            } else {
                emptyMap()
            }
            for ((content, info) in entries) {
                content.displayName = if (fileTitles.isEmpty()) {
                    "Parents: $symbolName"
                } else {
                    "Parents: ${fileTitles.getValue(info.filePath)} · $symbolName"
                }
            }
        }
    }

    private data class TabInfo(
        val symbolName: String,
        val filePath: String,
    )
}
