@file:Suppress("DEPRECATION")

import java.io.File
import java.time.Duration
import java.io.OutputStream.nullOutputStream
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
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
// CI checks submodules out without tags, so fall back to fetching them
// (the checkout URL rewrite applies to submodule remotes too).
fun resolveGrammarVersion(): String {
    fun run(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args).directory(grammarDir).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to out
    }
    val (code, tag) = run("git", "describe", "--tags", "--abbrev=0")
    if (code == 0 && tag.isNotEmpty()) return tag.removePrefix("v")
    // actions/checkout checks submodules out shallow without tags; unshallow
    // (no-op on full clones) so the describe succeeds.
    run("git", "fetch", "--unshallow", "--tags", "--quiet", "origin")
    val (code2, tag2) = run("git", "describe", "--tags", "--abbrev=0")
    check(code2 == 0 && tag2.isNotEmpty()) {
        "Cannot determine version for grammar $grammarName (git describe failed)"
    }
    return tag2.removePrefix("v")
}

val grammarVersion: String = if (grammarName == "smali") {
    // tree-sitter-smali tags releases as "stable" (no v1.0.0 tag exists).
    "1.0.0"
} else {
    resolveGrammarVersion()
}
// Release suffix: the grammar tag version was already published, so append
// .2 (x.x.x.2) for this project's publications.
val publishVersion: String = "$grammarVersion.2"
version = publishVersion
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
                val rel = genDir.toPath().relativize(scannerFile.toPath()).toString().replace("\\", "/")
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
                val rel = genDirPath.relativize(dir.toPath()).toString().replace("\\", "/")
                text += "\ninclude_directories($rel)\n"
            }
            val generatedHeaderPath = generatedHeaderDir.path.replace("\\", "/")
            if (!text.contains(generatedHeaderPath)) {
                text += "\ninclude_directories($generatedHeaderPath)\n"
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

// Per-platform JNI artifacts for this language (sdl-kmp layout).
val jniPlatformDeps = listOf(
    "darwin-aarch64", "darwin-x86_64",
    "linux-x86_64", "linux-aarch64", "windows-x86_64"
)

kotlin {
    jvm() {
        // The JVM artifact is pure Java; the native library comes from the
        // per-platform JNI artifacts published alongside it — one artifact per
        // language per platform (sdl-kmp layout). Within this build the
        // generated :jni:<lang>-<platform> projects are depended on directly
        // so consumers (e.g. :example) resolve everything as project deps;
        // the published POM still carries the five coordinates as runtime
        // dependencies for external consumers.
        sourceSets["jvmMain"].dependencies {
            jniPlatformDeps.forEach { suffix ->
                runtimeOnly(project(":jni:$grammarName-$suffix"))
            }
        }
    }

    // The published jvm POM must reference the five JNI artifacts by
    // coordinate (project deps are not rendered into the POM); inject them
    // as runtime dependencies alongside whatever the build already produced.
    val jniDepsForPom = jniPlatformDeps.map { suffix ->
        "cn.enaium.treesitter:treesitter-languages-$grammarName-kmp-jni-$suffix:$publishVersion"
    }
    tasks.withType<GenerateMavenPom>().configureEach {
        onlyIf { name.contains("JvmPublication") }
        doLast {
            val pom: java.io.File = destinationFile.get().asFile
            if (pom.exists()) {
                var text = pom.readText()
                if (!text.contains("treesitter-languages-$grammarName-kmp-jni-")) {
                    val deps = jniDepsForPom.joinToString("") {
                        val (g, a, v) = it.split(":")
                        """    <dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version><scope>runtime</scope></dependency>"""
                    }
                    text = text.replace(
                        "</dependencies>",
                        "$deps\n  </dependencies>"
                    )
                    pom.writeText(text)
                }
            }
        }
    }

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


// The JVM artifact is pure Java; the native libraries ship in the
// per-language per-platform JNI artifacts (see publishing block below).
tasks.named("jvmProcessResources") {
    dependsOn(generateTask)
}

// ===== Native grammar compilation =====
@Suppress("DEPRECATION")
tasks.withType<CInteropProcess>().configureEach {
    if (name.startsWith("cinteropTest")) return@configureEach

    val grammarName = project.name
    // Locate the Kotlin/Native prebuilt distribution (konanHome is deprecated).
    // On CI (JVM-only builds) there is no konan distribution; disable the
    // task instead of failing configuration.
    val konanRoot = File(System.getProperty("user.home"), ".konan")
    val konanDist = konanRoot.listFiles { f ->
        f.isDirectory && f.name.startsWith("kotlin-native-prebuilt")
    }?.maxByOrNull { it.lastModified() }
    if (konanDist == null) {
        enabled = false
        return@configureEach
    }
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

// CI can cross-compile a platform different from the host (e.g. linux-aarch64
// on an x64 runner) by overriding these properties.
val jniOs: String = (findProperty("jni.os") as String?) ?: hostOs
val jniArch: String = (findProperty("jni.arch") as String?) ?: hostArch

val jniLibsDir = layout.buildDirectory.dir("jni-libs")

val buildJni = tasks.register("buildJni") {
    group = "build"
    description = "Build the JNI library for platform $jniOs/$jniArch"
    dependsOn(generateTask)
    // -Pjni.os/-Pjni.arch change the output platform; without declaring them
    // as inputs a cross build after a native one would be skipped as
    // UP-TO-DATE and publish an empty jar.
    inputs.property("jniOs", jniOs)
    inputs.property("jniArch", jniArch)
    outputs.dir(jniLibsDir)
    doLast {
        val generatedDir = layout.buildDirectory.dir("generatedGrammar").get().asFile
        val buildDir = layout.buildDirectory.dir(".cmake/jni").get().asFile
        val installPrefix = jniLibsDir.get().asFile
        val installLibDir = "lib/$jniOs/$jniArch"
        val crossArgs = mutableListOf<String>()
        if (jniOs != hostOs || jniArch != hostArch) {
            // Cross-compile toolchain (used by CI for linux-aarch64, darwin-x86_64).
            if (jniOs == "macos") {
                crossArgs += "-DCMAKE_OSX_ARCHITECTURES=" +
                    if (jniArch == "aarch64") "arm64" else "x86_64"
            } else if (jniOs == "linux") {
                crossArgs += "-DCMAKE_SYSTEM_NAME=Linux"
                crossArgs += "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc"
                crossArgs += "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++"
                // FindJNI does not work when cross-compiling; point it at the
                // host JDK headers (arch-independent jni.h/jni_md.h).
                val javaHome = System.getenv("JAVA_HOME")
                if (javaHome != null) {
                    crossArgs += "-DJNI_INCLUDE_DIRS=${File(javaHome, "include").path}"
                }
            }
        }
        // Allow arbitrary extra CMake args (-Pjni.cmakeArgs=...).
        (findProperty("jni.cmakeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }?.let {
            crossArgs += it
        }
        runProcess(
            listOf(
                "cmake", "-S", generatedDir.path, "-B", buildDir.path,
                "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_INSTALL_PREFIX=${installPrefix.path}",
                "-DCMAKE_INSTALL_LIBDIR=$installLibDir"
            ) + crossArgs
        )
        runProcess(listOf("cmake", "--build", buildDir.path, "--config", "RelWithDebInfo"))
        runProcess(listOf("cmake", "--install", buildDir.path, "--config", "RelWithDebInfo"))
        // Guard against publishing empty jars: the native library must have
        // been installed for the target platform.
        val installed = File(installPrefix, installLibDir).listFiles()
            ?.filter { it.isFile }
            ?.any { it.name.startsWith("lib$jniLibName.") || it.name.startsWith("$jniLibName.") }
            ?: false
        check(installed) {
            "buildJni produced no native library for $jniOs/$jniArch " +
                "(expected in ${File(installPrefix, installLibDir).path})"
        }
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
mavenPublishing {
    coordinates("cn.enaium.treesitter", "treesitter-languages-${grammarName}-kmp", publishVersion)
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

