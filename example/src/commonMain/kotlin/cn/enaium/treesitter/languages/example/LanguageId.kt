package cn.enaium.treesitter.languages.example

/** The 24 grammars bundled by the language collection. */
enum class LanguageId(
    val id: String,
    val display: String,
    /** The wasm module path used on JS/Wasm targets. */
    val wasmModule: String
) {
    AGDA("agda", "Agda", "tree-sitter-wasm/agda/tree-sitter-agda.wasm"),
    BASH("bash", "Bash", "tree-sitter-wasm/bash/tree-sitter-bash.wasm"),
    C("c", "C", "tree-sitter-wasm/c/tree-sitter-c.wasm"),
    C_SHARP("c-sharp", "C#", "tree-sitter-wasm/c_sharp/tree-sitter-c_sharp.wasm"),
    CPP("cpp", "C++", "tree-sitter-wasm/cpp/tree-sitter-cpp.wasm"),
    CSS("css", "CSS", "tree-sitter-wasm/css/tree-sitter-css.wasm"),
    EMBEDDED_TEMPLATE(
        "embedded-template",
        "Embedded Template",
        "tree-sitter-wasm/embedded_template/tree-sitter-embedded_template.wasm"
    ),
    GO("go", "Go", "tree-sitter-wasm/go/tree-sitter-go.wasm"),
    HASKELL("haskell", "Haskell", "tree-sitter-wasm/haskell/tree-sitter-haskell.wasm"),
    HTML("html", "HTML", "tree-sitter-wasm/html/tree-sitter-html.wasm"),
    JAVA("java", "Java", "tree-sitter-wasm/java/tree-sitter-java.wasm"),
    JAVASCRIPT("javascript", "JavaScript", "tree-sitter-wasm/javascript/tree-sitter-javascript.wasm"),
    JSON("json", "JSON", "tree-sitter-wasm/json/tree-sitter-json.wasm"),
    JULIA("julia", "Julia", "tree-sitter-wasm/julia/tree-sitter-julia.wasm"),
    OCAML("ocaml", "OCaml", "tree-sitter-wasm/ocaml/tree-sitter-ocaml.wasm"),
    PHP("php", "PHP", "tree-sitter-wasm/php/tree-sitter-php.wasm"),
    PYTHON("python", "Python", "tree-sitter-wasm/python/tree-sitter-python.wasm"),
    REGEX("regex", "Regex", "tree-sitter-wasm/regex/tree-sitter-regex.wasm"),
    RUBY("ruby", "Ruby", "tree-sitter-wasm/ruby/tree-sitter-ruby.wasm"),
    RUST("rust", "Rust", "tree-sitter-wasm/rust/tree-sitter-rust.wasm"),
    SCALA("scala", "Scala", "tree-sitter-wasm/scala/tree-sitter-scala.wasm"),
    TSX("tsx", "TSX", "tree-sitter-wasm/tsx/tree-sitter-tsx.wasm"),
    TYPESCRIPT("typescript", "TypeScript", "tree-sitter-wasm/typescript/tree-sitter-typescript.wasm"),
    VERILOG("verilog", "Verilog", "tree-sitter-wasm/verilog/tree-sitter-verilog.wasm");
}
