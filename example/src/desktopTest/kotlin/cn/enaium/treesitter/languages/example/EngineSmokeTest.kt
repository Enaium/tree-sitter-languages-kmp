package cn.enaium.treesitter.languages.example

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Smoke tests for the playground engine on the JVM/desktop target. */
class EngineSmokeTest {

    @Test
    fun parsesJavaAndQueries() = runBlocking {
        val engine = createEngine(LanguageId.JAVA)
        val tree = engine.parse("class A { int x; }")

        assertEquals("program", tree.root.type)
        val classDecl = tree.root.children.firstOrNull { it.type == "class_declaration" }
        assertTrue(classDecl != null, "expected class_declaration node")

        val matches = engine.queryMatches("(class_declaration) @class", tree)
        assertTrue(matches.isNotEmpty(), "query should match the class_declaration")

        val text = classDecl!!.textOf("class A { int x; }")
        assertTrue(text.startsWith("class A"))
    }

    @Test
    fun parsesPythonAndQueries() = runBlocking {
        val engine = createEngine(LanguageId.PYTHON)
        val tree = engine.parse("def fib(n):\n    return n\n")

        assertEquals("module", tree.root.type)
        val def = tree.root.children.firstOrNull { it.type == "function_definition" }
        assertTrue(def != null, "expected function_definition node")

        val matches = engine.queryMatches("(function_definition name: (identifier) @name)", tree)
        assertTrue(matches.isNotEmpty(), "query should match the function name")
    }

    @Test
    fun invalidQueryReturnsEmpty() = runBlocking {
        val engine = createEngine(LanguageId.JSON)
        val tree = engine.parse("{\"a\": 1}")
        val matches = engine.queryMatches("(((", tree)
        assertTrue(matches.isEmpty(), "invalid query must not crash")
    }
}
