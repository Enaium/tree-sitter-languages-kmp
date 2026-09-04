@file:Suppress("DEPRECATION")

import java.io.File
import java.time.Duration
import java.io.OutputStream.nullOutputStream
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.support.useToRun
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool

inline val File.unixPath: String
    get() = if (!os.isWindows) path else path.replace("\\", "/")

val os: OperatingSystem = OperatingSystem.current()

/** Run an external process, throwing on non-zero exit. */
fun runProcess(command: List<String>, workingDir: File? = null) {
    val pb = ProcessBuilder(command)
    if (workingDir != null) pb.directory(workingDir)
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) {
        throw GradleException(
            "Command failed ($code): ${command.joinToString(" ")}\n$output"
        )
    }
}

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

apply<cn.enaium.treesitter.languages.plugin.GrammarPlugin>()

// Versions mirrored from gradle/libs.versions.toml.
private val ktreesitterVersion = "0.25.1"

// ===== Grammar identity, derived from the module name =====
val grammarName: String = project.name
val className: String =
    "TreeSitter" + grammarName.split("-").joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
val packageName: String = "cn.enaium.treesitter.languages." + grammarName.replace("-", "")
val cSymbol: String = "tree_sitter_" + grammarName.replace("-", "_")

val grammarDir: File = projectDir.resolve("tree-sitter-$grammarName")
val libsDir: File = layout.buildDirectory.dir("libs").get().asFile

// Version each module by its grammar repository tag (e.g. tree-sitter-java
// v0.23.5 -> 0.23.5) so published artifacts track the grammar version.
val grammarVersion: String = run {
    val process = ProcessBuilder(
        "git", "-C", grammarDir.path, "describe", "--tags", "--abbrev=0"
    )
    val tag = process.start().inputStream.bufferedReader().readText().trim()
    tag.removePrefix("v")
}
version = grammarVersion
val jniLibName: String = "ktreesitter-$grammarName"

// Grammar source directories inside the submodule, per language.
val grammarSrcDirs: List<String> = when (grammarName) {
    "php" -> listOf("php/src")
    "typescript" -> listOf("typescript/src")
    "tsx" -> listOf("tsx/src")
    "ocaml" -> listOf("grammars/ocaml/src")
    "xml" -> listOf("xml/src")
    "markdown" -> listOf("tree-sitter-markdown/src")
    else -> listOf("src")
}

// Extra C header include directories (some grammars keep headers under a
// bindings/c/tree_sitter subdirectory instead of bindings/c).
val grammarHeaderDirs: List<String> = when (grammarName) {
    "lua", "yaml", "diff" -> listOf("bindings/c/tree_sitter")
    "markdown" -> listOf("tree-sitter-markdown/bindings/c/tree_sitter")
    "smali" -> listOf("bindings/swift")
    else -> emptyList()
}

val grammarFiles: List<File> = grammarSrcDirs.flatMap { dir ->
    val srcDir = grammarDir.resolve(dir)
    listOf("parser.c", "scanner.c", "scanner.cc")
        .map { srcDir.resolve(it) }
        .filter { it.isFile }
}.distinct()

val generatedHeaderDir: File = layout.buildDirectory.dir("generated-header").get().asFile

// Some grammars do not ship bindings/c/tree-sitter-<name>.h (it is normally
// produced by the tree-sitter CLI). Generate a minimal header declaring the
// language entry point so cinterop and the JNI binding compile.
val generateGrammarHeader = tasks.register("generateGrammarHeader") {
    group = "build"
    description = "Generate a minimal language header when the grammar lacks one"
    inputs.property("grammarName", grammarName)
    inputs.property("cSymbol", cSymbol)
    outputs.dir(generatedHeaderDir)
    doLast {
        val existing = grammarDir.resolve("bindings/c/tree-sitter-$grammarName.h")
        if (!existing.isFile) {
            generatedHeaderDir.mkdirs()
            val guard = "TREE_SITTER_" + grammarName.uppercase().replace("-", "_") + "_H_"
            File(generatedHeaderDir, "tree-sitter-$grammarName.h").writeText(
                """
                #ifndef $guard
                #define $guard

                typedef struct TSLanguage TSLanguage;

                #ifdef __cplusplus
                extern "C" {
                #endif

                const TSLanguage *$cSymbol(void);

                #ifdef __cplusplus
                }
                #endif

                #endif
                """.trimIndent() + "\n"
            )
        }
    }
}

// The plugin-generated CMakeLists.txt only references the grammar's bindings/c
// directory; append our generated header dir so JNI/android builds find it.
val patchCmakeForHeader = tasks.register("patchCmakeForHeader") {
    group = "build"
    description = "Fix the generated CMakeLists.txt for non-standard grammar layouts"
    dependsOn(generateGrammarHeader)
    doLast {
        val cmakeFile = generateTask.cmakeListsFile.get().asFile
        if (cmakeFile.isFile) {
            var text = cmakeFile.readText()
            // Rewrite the grammar source paths for grammars whose sources do not
            // live in <repo>/src (ocaml: grammars/ocaml/src, typescript/tsx: subdirs).
            val srcRel = "tree-sitter-$grammarName/src/"
            if (text.contains(srcRel)) {
                val actualSrcRel = "tree-sitter-$grammarName/" + grammarSrcDirs.first() + "/"
                text = text.replace(srcRel, actualSrcRel)
            }
            // The plugin only looks for scanner.c at <repo>/src/scanner.c; add it
            // explicitly when the grammar actually has one elsewhere.
            val actualSrcDir = grammarDir.resolve(grammarSrcDirs.first())
            val scannerFile = listOf("scanner.c", "scanner.cc")
                .map { actualSrcDir.resolve(it) }
                .firstOrNull { it.isFile }
            if (scannerFile != null && !text.contains("scanner")) {
                val genDir = layout.buildDirectory.dir("generatedGrammar").get().asFile
                val rel = genDir.toPath().relativize(scannerFile.toPath()).toString()
                val addLib = "add_library("
                val idx = text.indexOf(addLib)
                if (idx >= 0) {
                    val close = text.indexOf(')', idx)
                    text = text.substring(0, close) + " $rel" + text.substring(close)
                }
            }
            val genDirPath = layout.buildDirectory.dir("generatedGrammar").get().asFile.toPath()
            val extraDirs = grammarSrcDirs + listOf("common") + grammarHeaderDirs
            extraDirs.map { grammarDir.resolve(it) }.filter { it.isDirectory }.forEach { dir ->
                val rel = genDirPath.relativize(dir.toPath()).toString()
                text += "\ninclude_directories($rel)\n"
            }
            if (!text.contains(generatedHeaderDir.path)) {
                text += "\ninclude_directories(${generatedHeaderDir.path})\n"
            }
            cmakeFile.writeText(text)
        }
    }
}

// ===== Grammar plugin configuration =====
// NOTE: inside the `grammar {}` block the receiver shadows top-level vals;
// use the precomputed strings to avoid self-referencing the properties.
val grammarConfigName: String = grammarName
val grammarConfigClass: String = className
val grammarConfigPackage: String = packageName
val grammarConfigSymbol: String = cSymbol

extensions.configure<cn.enaium.treesitter.languages.plugin.GrammarExtension>("grammar") {
    baseDir.set(grammarDir)
    grammarName.set(grammarConfigName)
    className.set(grammarConfigClass)
    packageName.set(grammarConfigPackage)
    languageMethods.set(mapOf("language" to grammarConfigSymbol))
}

val generateTask = tasks.named<cn.enaium.treesitter.languages.plugin.GrammarFilesTask>("generateGrammarFiles").get()

generateTask.finalizedBy(patchCmakeForHeader)

// ===== Kotlin targets =====
fun KotlinNativeTarget.treesitter() {
    compilations.configureEach {
        cinterops.create("treesitter") {
            definitionFile.set(generateTask.interopFile)
            includeDirs.allHeaders(grammarDir.resolve("bindings/c"))
            grammarHeaderDirs.forEach { d ->
                includeDirs.allHeaders(grammarDir.resolve(d))
            }
            includeDirs.allHeaders(generatedHeaderDir)
            extraOpts("-libraryPath", File(libsDir, konanTarget.name).path)
            tasks.getByName(interopProcessingTaskName).mustRunAfter(generateTask)
            tasks.getByName(interopProcessingTaskName).dependsOn(generateGrammarHeader)
        }
    }
}

kotlin {
    jvm()

    androidLibrary {
        namespace = packageName
        compileSdk = (property("sdk.version.compile") as String).toInt()
        minSdk = (property("sdk.version.min") as String).toInt()
        buildToolsVersion = "36.0.0"
    }

    linuxX64 { treesitter() }
    linuxArm64 { treesitter() }
    mingwX64 { treesitter() }
    macosX64 { treesitter() }
    macosArm64 { treesitter() }
    iosArm64 { treesitter() }
    iosSimulatorArm64 { treesitter() }

    applyDefaultHierarchyTemplate()

    jvmToolchain(17)

    sourceSets {
        configureEach {
            kotlin.srcDir(
                layout.buildDirectory.dir("generatedGrammar/src/$name/kotlin")
            )
        }

        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        getByName("jvmMain") {
            resources.srcDir(layout.buildDirectory.dir("jni-libs"))
        }

        getByName("jvmTest") {
            dependencies {
                implementation("io.github.tree-sitter:ktreesitter:$ktreesitterVersion")
            }
        }

        getByName("nativeTest") {
            dependencies {
                implementation("io.github.tree-sitter:ktreesitter:$ktreesitterVersion")
            }
        }

    }
}


tasks.withType<AbstractKotlinCompileTool<*>>().configureEach {
    dependsOn(generateTask)
}

// Sources jars for all targets scan the generated source trees.
tasks.matching { it.name.lowercase().endsWith("sourcesjar") }
    .configureEach {
        dependsOn(generateTask)
    }


// AGP 9's KMP android plugin scans the androidMain source set for
// baselineProfiles; that location overlaps the generated grammar tree, so
// declare the dependency explicitly.
tasks.matching { it.name.startsWith("prepareAndroidMainArtProfile") }.configureEach {
    dependsOn(generateTask)
}

tasks.matching { it.name == "androidSourcesJar" || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(generateTask)
}


tasks.named("jvmProcessResources") {
    dependsOn(generateTask, buildJni)
}

// The KMP jvmJar packages jvmMain resources directly; ensure the JNI library
// is installed before the jar is assembled.
tasks.named("jvmJar") {
    dependsOn(buildJni)
}

// ===== Native grammar compilation =====
@Suppress("DEPRECATION")
tasks.withType<CInteropProcess>().configureEach {
    if (name.startsWith("cinteropTest")) return@configureEach

    val grammarName = project.name
    // Locate the Kotlin/Native prebuilt distribution (konanHome is deprecated).
    val konanRoot = File(System.getProperty("user.home"), ".konan")
    val konanDist = konanRoot.listFiles { f ->
        f.isDirectory && f.name.startsWith("kotlin-native-prebuilt")
    }?.maxByOrNull { it.lastModified() }
        ?: error("Kotlin/Native distribution not found under $konanRoot")
    val runKonan = konanDist.resolve("bin")
        .resolve(if (os.isWindows) "run_konan.bat" else "run_konan").path
    val libFile = File(libsDir, konanTarget.name).resolve("libtree-sitter-$grammarName.a")
    val outRoot = layout.buildDirectory.dir("grammar/${konanTarget.name}").get().asFile

    doFirst {
        val objectFiles = grammarFiles.mapIndexed { index, src ->
            val workDir = File(outRoot, index.toString()).apply { mkdirs() }
            val argsFile = File.createTempFile("args", null)
            argsFile.deleteOnExit()
            argsFile.writer().useToRun {
                (grammarSrcDirs + listOf("common")).filter {
                    grammarDir.resolve(it).isDirectory
                }.forEach { dir ->
                    write("-I" + grammarDir.resolve(dir).unixPath + "\n")
                }
                write("-I" + grammarDir.resolve("bindings/c").unixPath + "\n")
                write("-DTREE_SITTER_HIDE_SYMBOLS\n")
                write("-fvisibility=hidden\n")
                write("-O2\n")
                write("-g\n")
                if (src.name.endsWith(".cc")) {
                    write("-std=c++14\n")
                } else {
                    write("-std=c11\n")
                }
                write("-c\n")
                write(src.unixPath + "\n")
            }

            runProcess(
                listOf(runKonan, "clang", "clang", konanTarget.name, "@" + argsFile.path),
                workingDir = workDir
            )

            File(workDir, src.nameWithoutExtension + ".o")
        }

        runProcess(
            listOf(runKonan, "llvm", "llvm-ar", "rcs", libFile.path) + objectFiles.map { it.path },
            workingDir = projectDir
        )
    }

    inputs.files(*grammarFiles.toTypedArray())
    outputs.file(libFile)
}

// ===== JVM JNI library via CMake =====
val hostOs: String = when {
    os.isMacOsX -> "macos"
    os.isLinux -> "linux"
    os.isWindows -> "windows"
    else -> error("Unsupported host OS")
}
val hostArch: String = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "aarch64"
    else -> "x64"
}

val jniLibsDir = layout.buildDirectory.dir("jni-libs")

val buildJni = tasks.register("buildJni") {
    group = "build"
    description = "Build the JNI library for the host platform ($hostOs/$hostArch)"
    dependsOn(generateTask)
    outputs.dir(jniLibsDir)
    doLast {
        val generatedDir = layout.buildDirectory.dir("generatedGrammar").get().asFile
        val buildDir = layout.buildDirectory.dir(".cmake/jni").get().asFile
        val installPrefix = jniLibsDir.get().asFile
        val installLibDir = "lib/$hostOs/$hostArch"
        runProcess(
            listOf(
                "cmake", "-S", generatedDir.path, "-B", buildDir.path,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_INSTALL_PREFIX=${installPrefix.path}",
                "-DCMAKE_INSTALL_LIBDIR=$installLibDir"
            )
        )
        runProcess(listOf("cmake", "--build", buildDir.path))
        runProcess(listOf("cmake", "--install", buildDir.path))
    }
}

tasks.named("jvmTest") { dependsOn(buildJni) }

// ===== Android =====
// AGP 9's KMP plugin (`com.android.kotlin.multiplatform.library`) has no
// externalNativeBuild DSL, so the JNI shared library is compiled directly with
// the NDK toolchain and dropped into the androidMain jniLibs source dir.
val androidJniLibsDir: File = projectDir.resolve("src/androidMain/jniLibs")
val androidAbis: List<Pair<String, String>> = listOf(
    "arm64-v8a" to "aarch64-linux-android23-clang",
    "armeabi-v7a" to "armv7a-linux-androideabi23-clang",
    "x86_64" to "x86_64-linux-android23-clang"
)

val buildAndroidJni = tasks.register("buildAndroidJni") {
    group = "build"
    description = "Compile the JNI library for Android ABIs with the NDK"
    dependsOn(generateTask, generateGrammarHeader)
    val sdkDir = File(localProperties("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "")
    val ndkDir = File(sdkDir, "ndk/${project.property("ndk.version")}")
    inputs.files(*grammarFiles.toTypedArray())
    inputs.dir(generatedHeaderDir)
    outputs.dir(androidJniLibsDir)
    doLast {
        val toolchain = File(ndkDir, "toolchains/llvm/prebuilt")
        val hostDir = toolchain.listFiles()?.firstOrNull() ?: error("NDK toolchain not found in $toolchain")
        androidAbis.forEach { (abi, clangName) ->
            val clang = File(hostDir, "bin/$clangName")
            val outDir = File(androidJniLibsDir, abi)
            outDir.mkdirs()
            val objects = grammarFiles.mapIndexed { index, src ->
                val isCpp = src.name.endsWith(".cc")
                val obj = File(buildDir, "ndk/$abi/${index}.o")
                obj.parentFile.mkdirs()
                val args = mutableListOf(
                    clang.path,
                    "-std=${if (isCpp) "c++14" else "c11"}",
                    "-O2", "-fPIC", "-c",
                    "-DTREE_SITTER_HIDE_SYMBOLS",
                    "-I" + grammarDir.resolve("bindings/c").path,
                    "-I" + generatedHeaderDir.path
                )
                grammarHeaderDirs.forEach { d ->
                    args.add("-I" + grammarDir.resolve(d).path)
                }
                grammarSrcDirs.forEach { dir ->
                    args.add("-I" + grammarDir.resolve(dir).path)
                }
                args.addAll(listOf(src.path, "-o", obj.path))
                runProcess(args)
                obj
            }
            val binding = buildDir.resolve("ndk/$abi/binding.o")
            binding.parentFile.mkdirs()
            runProcess(
                listOf(
                    clang.path, "-std=c11", "-O2", "-fPIC", "-c",
                    "-DTREE_SITTER_HIDE_SYMBOLS",
                    "-I" + grammarDir.resolve("bindings/c").path,
                    "-I" + generatedHeaderDir.path,
                ) + grammarHeaderDirs.map { "-I" + grammarDir.resolve(it).path } + listOf(
                    File(layout.buildDirectory.dir("generatedGrammar").get().asFile, "src/jni/binding.c").path,
                    "-o", binding.path
                )
            )
            runProcess(
                listOf(clang.path, "-shared", "-o", File(outDir, "lib$jniLibName.so").path) +
                    objects.map { it.path } + listOf(binding.path)
            )
        }
    }
}

tasks.matching {
    it.name == "androidPreBuild" || it.name == "assembleAndroidMain"
}.configureEach {
    dependsOn(buildAndroidJni)
}

fun localProperties(key: String): String? {
    val f = File(rootProject.projectDir, "local.properties")
    return if (f.isFile) {
        f.readLines().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
    } else null
}

// ===== JVM compilation settings =====
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ===== Publishing (com.vanniktech.maven.publish) =====
// Each platform is published as its own artifact:
// cn.enaium.treesitter:treesitter-languages-<lang>-kmp-<platform>
mavenPublishing {
    coordinates("cn.enaium.treesitter", "treesitter-languages-${grammarName}-kmp", project.version.toString())
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name = "$className"
        description = "$grammarName grammar for tree-sitter"
        url = "https://github.com/Enaium/tree-sitter-languages-kmp"
        licenses {
            license {
                name = "MIT License"
                url = "https://spdx.org/licenses/MIT.html"
            }
        }
        developers {
            developer {
                id = "enaium"
                name = "Enaium"
                url = "https://github.com/Enaium"
            }
        }
        scm {
            url = "https://github.com/Enaium/tree-sitter-languages-kmp"
            connection = "scm:git:git@github.com:Enaium/tree-sitter-languages-kmp.git"
            developerConnection = "scm:git:git@github.com:Enaium/tree-sitter-languages-kmp.git"
        }
    }
}
