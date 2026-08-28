package cn.enaium.treesitter.languages.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A plugin that generates Kotlin bindings and JNI/CMake scaffolding for a
 * tree-sitter grammar, ported from the upstream kotlin-tree-sitter plugin.
 */
class GrammarPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("grammar", GrammarExtension::class.java)
        extension.interopName.convention("grammar")
        extension.baseDir.convention(
            project.projectDir.parentFile.parentFile
        )
        extension.libraryName.convention(
            extension.grammarName.map { name -> "ktreesitter-$name" }
        )
        extension.languageMethods.convention(
            extension.grammarName.map { name ->
                mapOf("language" to "tree_sitter_$name")
            }
        )

        val taskProvider = project.tasks.register(
            "generateGrammarFiles", GrammarFilesTask::class.java
        )
        taskProvider.configure {
            grammarDir.set(extension.baseDir.get())
            grammarName.set(extension.grammarName.get())
            interopName.set(extension.interopName.get())
            libraryName.set(extension.libraryName.get())
            packageName.set(extension.packageName.get())
            className.set(extension.className.get())
            languageMethods.set(extension.languageMethods.get())

            generatedSrc.set(project.layout.buildDirectory.dir("generatedGrammar/src"))
            cmakeListsFile.set(project.layout.buildDirectory.file("generatedGrammar/CMakeLists.txt"))
            interopFile.set(
                project.layout.buildDirectory.file("generatedGrammar/src/nativeInterop/$interopName.def")
            )

            outputs.dir(generatedSrc)
            outputs.files(cmakeListsFile, interopFile)
        }
    }
}
