package dev.firefly4ik.gofilerelationgraph.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class FileTitleDisambiguatorTest {
    @Test
    fun `different file names do not include parent directories`() {
        val titles = FileTitleDisambiguator.disambiguate(
            listOf("/project/value/rater.go", "/project/value/limit.go"),
            "/project",
        )

        assertEquals("rater.go", titles.getValue("/project/value/rater.go"))
        assertEquals("limit.go", titles.getValue("/project/value/limit.go"))
    }

    @Test
    fun `equal file names include the nearest different parent`() {
        val titles = FileTitleDisambiguator.disambiguate(
            listOf("/project/value/rater.go", "/project/limit/rater.go"),
            "/project",
        )

        assertEquals("value/rater.go", titles.getValue("/project/value/rater.go"))
        assertEquals("limit/rater.go", titles.getValue("/project/limit/rater.go"))
    }

    @Test
    fun `equal suffixes expand until paths become different`() {
        val titles = FileTitleDisambiguator.disambiguate(
            listOf("/project/q/w/e.go", "/project/w/w/e.go"),
            "/project",
        )

        assertEquals("q/w/e.go", titles.getValue("/project/q/w/e.go"))
        assertEquals("w/w/e.go", titles.getValue("/project/w/w/e.go"))
    }
}
