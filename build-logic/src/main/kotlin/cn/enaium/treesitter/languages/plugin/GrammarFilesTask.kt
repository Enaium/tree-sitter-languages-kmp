package cn.enaium.treesitter.languages.plugin

import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** The task that generates the Kotlin/JNI source files for a grammar. */
@CacheableTask
abstract class GrammarFilesTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val grammarDir: Property<File>

    @get:Input
    abstract val grammarName: Property<String>

    @get:Input
    abstract val interopName: Property<String>

    @get:Input
    abstract val libraryName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:Input
    abstract val languageMethods: MapProperty<String, String>

    @get:OutputDirectory
     abstract val generatedSrc: DirectoryProperty

    @get:OutputFile
    abstract val cmakeListsFile: RegularFileProperty

    @get:OutputFile
    abstract val interopFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val srcDir = generatedSrc.get().asFile
        srcDir.mkdirs()
        val srcPath = srcDir.toPath()
        generateCommon(srcPath)
        generateNative(srcPath)
        generateJvm(srcPath)
        generateAndroid(srcPath)
        generateJniBinding(srcPath)
        generateCmakeLists(srcPath)
        generateInterop()
    }

    private fun generateCommon(srcDir: Path) {
        val classFile = srcDir.resolve(
            "commonMain/kotlin/${packageName.get().replace('.', '/')}/${className.get()}.kt"
        )
        classFile.parent.toFile().mkdirs()
        val methods = languageMethods.get().keys.joinToString("\n\n") { "    fun $it(): Any" }
        val template = readResource("common.kt.in")
            .replace("@PACKAGE@", packageName.get())
            .replace("@CLASS@", className.get())
            .replace("@METHODS@", methods)
        writeFile(classFile.toFile(), template)
    }

    private fun generateNative(srcDir: Path) {
        val classFile = srcDir.resolve(
            "nativeMain/kotlin/${packageName.get().replace('.', '/')}/${className.get()}.kt"
        )
        classFile.parent.toFile().mkdirs()
        val imports = languageMethods.get().values.joinToString("\n\n") {
            "import ${packageName.get()}.internal.$it"
        }
        val methods = languageMethods.get().entries.joinToString("\n\n") {
            "    actual fun ${it.key}(): Any = ${it.value}()!!"
        }
        val template = readResource("native.kt.in")
            .replace("@PACKAGE@", packageName.get())
            .replace("@CLASS@", className.get())
            .replace("@IMPORTS@", imports)
            .replace("@METHODS@", methods)
        writeFile(classFile.toFile(), template)
    }

    private fun generateJvm(srcDir: Path) {
        val classFile = srcDir.resolve(
            "jvmMain/kotlin/${packageName.get().replace('.', '/')}/${className.get()}.kt"
        )
        classFile.parent.toFile().mkdirs()
        val methods = languageMethods.get().entries.joinToString("\n\n") {
            """
                actual fun ${it.key}(): Any = ${it.value}()

                @JvmStatic
                private external fun ${it.value}(): Long
            """.trimIndent()
        }
        val template = readResource("jvm.kt.in")
            .replace("@PACKAGE@", packageName.get())
            .replace("@CLASS@", className.get())
            .replace("@LIBRARY@", libraryName.get())
            .replace("@METHODS@", methods)
        writeFile(classFile.toFile(), template)
    }

    private fun generateAndroid(srcDir: Path) {
        val classFile = srcDir.resolve(
            "androidMain/kotlin/${packageName.get().replace('.', '/')}/${className.get()}.kt"
        )
        classFile.parent.toFile().mkdirs()
        val methods = languageMethods.get().entries.joinToString("\n\n") {
            """
                actual fun ${it.key}(): Any = ${it.value}()

                @JvmStatic
                @CriticalNative
                private external fun ${it.value}(): Long
            """.trimIndent()
        }
        val template = readResource("android.kt.in")
            .replace("@PACKAGE@", packageName.get())
            .replace("@CLASS@", className.get())
            .replace("@LIBRARY@", libraryName.get())
            .replace("@METHODS@", methods)
        writeFile(classFile.toFile(), template)
    }

    private fun generateJniBinding(srcDir: Path) {
        val jniBinding = srcDir.resolve("jni").resolve("binding.c")
        jniBinding.parent.toFile().mkdirs()
        val jniClassName = jniTransform(className.get())
        val jniPackageName = jniTransform(packageName.get()).replace('.', '_')
        val jniPrefix = "Java_${jniPackageName}_${jniClassName}_"
        val methods = languageMethods.get().values.joinToString("") {
            "NATIVE_FUNCTION($jniPrefix${jniTransform(it)}) {\n    return (jlong)$it();\n}\n"
        }
        val template = readResource("jni.c.in")
            .replace("@GRAMMAR@", grammarName.get())
            .replace("@FUNCTIONS@", methods)
        writeFile(jniBinding.toFile(), template)
    }

    private fun generateCmakeLists(srcDir: Path) {
        val jniBinding = srcDir.resolve("jni").resolve("binding.c")
        val cBindingDir = grammarDir.get().toPath().resolve("bindings/c")
        val includeDirs = "${relative(cBindingDir)} ${relative(cBindingDir.resolve("tree-sitter"))}"
        val sources = "${relative(jniBinding)} ${srcFiles()}"
        val template = readResource("CMakeLists.txt.in")
            .replace("@LIBRARY@", libraryName.get())
            .replace("@INCLUDE@", includeDirs)
            .replace("@SOURCES@", sources)
        writeFile(cmakeListsFile.get().asFile, template)
    }

    private fun generateInterop() {
        val interopFile = this.interopFile.get().asFile
        interopFile.parentFile.mkdirs()
        val template = readResource("interop.def.in")
            .replace("@PACKAGE@", packageName.get())
            .replace("@GRAMMAR@", grammarName.get())
        writeFile(interopFile, template)
    }

    private fun relative(file: Path): String =
        generatedSrc.get().asFile.toPath().parent.relativize(file).toString()

    private fun srcFiles(): String {
        val grammarSrcDir = grammarDir.get().toPath().resolve("src")
        val scannerFile = grammarSrcDir.resolve("scanner.c")
        return if (!scannerFile.toFile().exists()) {
            relative(grammarSrcDir.resolve("parser.c"))
        } else {
            "${relative(grammarSrcDir.resolve("parser.c"))} ${relative(scannerFile)}"
        }
    }

    private fun readResource(file: String): String {
        val stream = javaClass.getResourceAsStream("/$file")
            ?: throw GradleException("Failed to read resource file: $file")
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    private fun writeFile(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun jniTransform(input: String): String = buildString {
        for (c in input) {
            when (c) {
                '_' -> append("_1")
                ';' -> append("_2")
                '[' -> append("_3")
                else -> if (c.code <= 0x7F) append(c) else append("_0%04x".format(c.code))
            }
        }
    }
}
