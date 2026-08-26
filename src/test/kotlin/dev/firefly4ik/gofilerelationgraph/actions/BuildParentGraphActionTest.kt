package dev.firefly4ik.gofilerelationgraph.actions

import com.goide.psi.GoFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildParentGraphActionTest : BasePlatformTestCase() {
    fun testUsesContainingNamedFunctionInsteadOfCallUnderCaret() {
        val file = myFixture.configureByText(
            "main.go",
            """
                package main

                func Parent() {
                    Child()
                }
            """.trimIndent(),
        ) as GoFile
        val caret = file.text.indexOf("Child")

        val anchor = BuildParentGraphAction().findAnchor(file, caret)

        assertEquals("Parent", anchor?.name)
    }

    fun testReturnsNearestNamedFunctionForAnonymousFunction() {
        val file = myFixture.configureByText(
            "main.go",
            """
                package main

                func Register() {
                    callback := func() {
                        Child()
                    }
                    callback()
                }
            """.trimIndent(),
        ) as GoFile
        val caret = file.text.indexOf("Child")

        val anchor = BuildParentGraphAction().findAnchor(file, caret)

        assertEquals("Register", anchor?.name)
    }

    fun testReturnsNullOutsideFunction() {
        val file = myFixture.configureByText("main.go", "package main\n\nvar value = 1") as GoFile

        assertNull(BuildParentGraphAction().findAnchor(file, file.text.indexOf("value")))
    }
}
