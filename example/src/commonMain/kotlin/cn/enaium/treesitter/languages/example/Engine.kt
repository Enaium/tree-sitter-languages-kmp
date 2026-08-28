package cn.enaium.treesitter.languages.example

/**
 * A platform-neutral syntax tree node, extracted from the underlying
 * tree-sitter implementation so the UI never touches native handles.
 */
data class SyntaxNode(
    val id: Long,
    val type: String,
    val startByte: Int,
    val endByte: Int,
    val startRow: Int,
    val startColumn: Int,
    val endRow: Int,
    val endColumn: Int,
    val children: List<SyntaxNode>
) {
    /** The source text spanned by this node. */
    fun textOf(source: String): String {
        val bytes = source.encodeToByteArray()
        if (startByte < 0 || endByte > bytes.size || startByte > endByte) return ""
        return bytes.copyOfRange(startByte, endByte).decodeToString()
    }
}

/** A parsed syntax tree. */
data class SyntaxTree(val root: SyntaxNode)

/**
 * The parsing engine abstraction. JVM/Android/Native use KTreeSitter.
 */
interface PlaygroundEngine {
    /** The display name of the bound language. */
    val languageName: String

    /** Parse [source], returning a platform-neutral tree. */
    suspend fun parse(source: String): SyntaxTree

    /**
     * Run [pattern] (a tree-sitter S-expression query) against the most
     * recently parsed tree and return the ids of matching nodes.
     */
    fun queryMatches(pattern: String, tree: SyntaxTree): Set<Long>
}

/** Create an engine for [languageId] on the current platform. */
expect suspend fun createEngine(languageId: LanguageId): PlaygroundEngine
