package cn.enaium.treesitter.languages.plugin

import java.io.File
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * The grammar configuration extension, mirroring the upstream
 * kotlin-tree-sitter plugin API.
 */
interface GrammarExtension {
    /** The base directory of the grammar. Default: `../..`. */
    val baseDir: Property<File>

    /** The name of the grammar. Required. */
    val grammarName: Property<String>

    /** The name of the C interop def file. Default: `grammar`. */
    val interopName: Property<String>

    /** The name of the JNI library. Default: `ktreesitter-${grammarName}`. */
    val libraryName: Property<String>

    /** The name of the package. Required. */
    val packageName: Property<String>

    /** The name of the class. Required. */
    val className: Property<String>

    /** A map of Java methods to C functions. Default: `language -> tree_sitter_${grammarName}`. */
    val languageMethods: MapProperty<String, String>
}
