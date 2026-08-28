package cn.enaium.treesitter.languages.example

import io.github.treesitter.ktreesitter.Language
import cn.enaium.treesitter.languages.agda.TreeSitterAgda
import cn.enaium.treesitter.languages.bash.TreeSitterBash
import cn.enaium.treesitter.languages.c.TreeSitterC
import cn.enaium.treesitter.languages.csharp.TreeSitterCSharp
import cn.enaium.treesitter.languages.cpp.TreeSitterCpp
import cn.enaium.treesitter.languages.css.TreeSitterCss
import cn.enaium.treesitter.languages.embeddedtemplate.TreeSitterEmbeddedTemplate
import cn.enaium.treesitter.languages.go.TreeSitterGo
import cn.enaium.treesitter.languages.haskell.TreeSitterHaskell
import cn.enaium.treesitter.languages.html.TreeSitterHtml
import cn.enaium.treesitter.languages.java.TreeSitterJava
import cn.enaium.treesitter.languages.javascript.TreeSitterJavascript
import cn.enaium.treesitter.languages.json.TreeSitterJson
import cn.enaium.treesitter.languages.julia.TreeSitterJulia
import cn.enaium.treesitter.languages.ocaml.TreeSitterOcaml
import cn.enaium.treesitter.languages.php.TreeSitterPhp
import cn.enaium.treesitter.languages.python.TreeSitterPython
import cn.enaium.treesitter.languages.regex.TreeSitterRegex
import cn.enaium.treesitter.languages.ruby.TreeSitterRuby
import cn.enaium.treesitter.languages.rust.TreeSitterRust
import cn.enaium.treesitter.languages.scala.TreeSitterScala
import cn.enaium.treesitter.languages.tsx.TreeSitterTsx
import cn.enaium.treesitter.languages.typescript.TreeSitterTypescript
import cn.enaium.treesitter.languages.verilog.TreeSitterVerilog

import io.github.treesitter.ktreesitter.Node
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Query
import io.github.treesitter.ktreesitter.QueryError
import io.github.treesitter.ktreesitter.Tree

actual suspend fun createEngine(languageId: LanguageId): PlaygroundEngine {
    val language = when (languageId) {
        LanguageId.AGDA -> Language(TreeSitterAgda.language())
        LanguageId.BASH -> Language(TreeSitterBash.language())
        LanguageId.C -> Language(TreeSitterC.language())
        LanguageId.C_SHARP -> Language(TreeSitterCSharp.language())
        LanguageId.CPP -> Language(TreeSitterCpp.language())
        LanguageId.CSS -> Language(TreeSitterCss.language())
        LanguageId.EMBEDDED_TEMPLATE -> Language(TreeSitterEmbeddedTemplate.language())
        LanguageId.GO -> Language(TreeSitterGo.language())
        LanguageId.HASKELL -> Language(TreeSitterHaskell.language())
        LanguageId.HTML -> Language(TreeSitterHtml.language())
        LanguageId.JAVA -> Language(TreeSitterJava.language())
        LanguageId.JAVASCRIPT -> Language(TreeSitterJavascript.language())
        LanguageId.JSON -> Language(TreeSitterJson.language())
        LanguageId.JULIA -> Language(TreeSitterJulia.language())
        LanguageId.OCAML -> Language(TreeSitterOcaml.language())
        LanguageId.PHP -> Language(TreeSitterPhp.language())
        LanguageId.PYTHON -> Language(TreeSitterPython.language())
        LanguageId.REGEX -> Language(TreeSitterRegex.language())
        LanguageId.RUBY -> Language(TreeSitterRuby.language())
        LanguageId.RUST -> Language(TreeSitterRust.language())
        LanguageId.SCALA -> Language(TreeSitterScala.language())
        LanguageId.TSX -> Language(TreeSitterTsx.language())
        LanguageId.TYPESCRIPT -> Language(TreeSitterTypescript.language())
        LanguageId.VERILOG -> Language(TreeSitterVerilog.language())
    }
    return KtreesitterEngine(language, languageId.display)
}

/** KTreeSitter-backed engine (JVM / Android / Native). */
internal class KtreesitterEngine(
    private val language: Language,
    override val languageName: String
) : PlaygroundEngine {

    private val parser = Parser(language)
    private var lastTree: Tree? = null

    override suspend fun parse(source: String): SyntaxTree {
        val tree = parser.parse(source)
        lastTree = tree
        return SyntaxTree(toNode(tree.rootNode))
    }

    private fun toNode(node: Node): SyntaxNode = SyntaxNode(
        id = node.id.toLong(),
        type = node.type,
        startByte = node.startByte.toInt(),
        endByte = node.endByte.toInt(),
        startRow = node.startPoint.row.toInt(),
        startColumn = node.startPoint.column.toInt(),
        endRow = node.endPoint.row.toInt(),
        endColumn = node.endPoint.column.toInt(),
        children = node.children.map { toNode(it) }
    )

    override fun queryMatches(pattern: String, tree: SyntaxTree): Set<Long> {
        val ktree = lastTree ?: return emptySet()
        return try {
            val query = Query(language, pattern)
            query(ktree.rootNode).matches()
                .flatMap { match -> match.captures.map { it.node.id.toLong() } }
                .toSet()
        } catch (e: QueryError) {
            emptySet()
        }
    }
}
