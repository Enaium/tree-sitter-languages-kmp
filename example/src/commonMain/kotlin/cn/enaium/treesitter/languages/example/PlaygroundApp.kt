package cn.enaium.treesitter.languages.example

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The main playground UI, mirroring the tree-sitter web playground. */
@Composable
fun PlaygroundApp() {
    MaterialTheme {
        var languageId by remember { mutableStateOf(LanguageId.JAVA) }
        var source by remember { mutableStateOf(defaultSource(LanguageId.JAVA)) }
        var query by remember { mutableStateOf("(class_declaration) @class") }
        var highlightMatches by remember { mutableStateOf(true) }
        var showOnlyMatches by remember { mutableStateOf(false) }
        var caseSensitive by remember { mutableStateOf(true) }

        var engine by remember { mutableStateOf<PlaygroundEngine?>(null) }
        var tree by remember { mutableStateOf<SyntaxTree?>(null) }
        var matches by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var selectedNode by remember { mutableStateOf<Long?>(null) }
        var engineError by remember { mutableStateOf<String?>(null) }

        // Create the engine for the selected language.
        LaunchedEffect(languageId) {
            engineError = null
            try {
                val e = createEngine(languageId)
                engine = e
                tree = e.parse(source)
                matches = emptySet()
                expanded = emptySet()
                selectedNode = null
            } catch (t: Throwable) {
                engineError = t.message ?: t.toString()
                engine = null
                tree = null
            }
        }

        // Parse whenever the source changes.
        LaunchedEffect(engine, source) {
            val e = engine ?: return@LaunchedEffect
            val t = e.parse(source)
            tree = t
            selectedNode = null
        }

        // Run the query.
        LaunchedEffect(engine, tree, query, caseSensitive) {
            val e = engine ?: return@LaunchedEffect
            val t = tree ?: return@LaunchedEffect
            matches = if (query.isBlank()) {
                emptySet()
            } else {
                e.queryMatches(normalizePattern(query, caseSensitive), t)
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Language selector row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Language:", fontWeight = FontWeight.Bold)
                LanguageDropdown(languageId) { languageId = it; source = defaultSource(it) }
                engineError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Query row.
            QueryBar(
                query = query,
                onQueryChange = { query = it },
                highlightMatches = highlightMatches,
                onHighlightMatches = { highlightMatches = it },
                showOnlyMatches = showOnlyMatches,
                onShowOnlyMatches = { showOnlyMatches = it },
                caseSensitive = caseSensitive,
                onCaseSensitive = { caseSensitive = it }
            )

            Spacer(Modifier.height(8.dp))

            // Source editor + syntax tree.
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceEditor(
                    source = source,
                    onSourceChange = { source = it },
                    tree = tree,
                    matches = matches,
                    highlightMatches = highlightMatches,
                    selectedNode = selectedNode,
                    modifier = Modifier.weight(1f).fillMaxSize()
                )
                TreePanel(
                    tree = tree,
                    source = source,
                    matches = matches,
                    showOnlyMatches = showOnlyMatches,
                    expanded = expanded,
                    onToggle = { id ->
                        expanded = if (id in expanded) expanded - id else expanded + id
                        selectedNode = id
                    },
                    modifier = Modifier.weight(1f).fillMaxSize()
                )
            }
        }
    }
}

/** A dropdown listing every grammar. */
@Composable
private fun LanguageDropdown(current: LanguageId, onSelect: (LanguageId) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(current.display)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LanguageId.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.display) },
                    onClick = { onSelect(lang); open = false }
                )
            }
        }
    }
}

/** The query input plus the option checkboxes. */
@Composable
private fun QueryBar(
    query: String,
    onQueryChange: (String) -> Unit,
    highlightMatches: Boolean,
    onHighlightMatches: (Boolean) -> Unit,
    showOnlyMatches: Boolean,
    onShowOnlyMatches: (Boolean) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitive: (Boolean) -> Unit
) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Query") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptionCheckbox("Highlight matches", highlightMatches, onHighlightMatches)
            OptionCheckbox("Show only matches", showOnlyMatches, onShowOnlyMatches)
            OptionCheckbox("Case sensitive", caseSensitive, onCaseSensitive)
        }
    }
}

@Composable
private fun OptionCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, fontSize = 13.sp)
    }
}

/** The source code editor with query/selection highlighting. */
@Composable
private fun SourceEditor(
    source: String,
    onSourceChange: (String) -> Unit,
    tree: SyntaxTree?,
    matches: Set<Long>,
    highlightMatches: Boolean,
    selectedNode: Long?,
    modifier: Modifier = Modifier
) {
    // Hoist the full TextFieldValue (including cursor selection) so typing
    // does not reset the caret to the start. Only the annotated *text* is
    // swapped into the value; selection survives via copy(text = ...).
    var editorValue by remember { mutableStateOf(TextFieldValue(source)) }

    // Reset the editor when the source is replaced externally (language switch).
    LaunchedEffect(source) {
        if (editorValue.text != source) {
            editorValue = TextFieldValue(source)
        }
    }

    // Build the highlighted text by annotating ranges of the *same* editor
    // text. Appending highlighted substrings would duplicate the text and
    // break the text field's cursor/selection handling.
    val annotated = remember(editorValue.text, tree, matches, highlightMatches, selectedNode) {
        val text = editorValue.text
        val ranges = mutableListOf<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>()
        val t = tree
        if (t != null && highlightMatches && matches.isNotEmpty()) {
            forEachNode(t.root) { n ->
                if (n.id in matches) {
                    val start = text.byteToCharIndex(n.startByte)
                    val end = text.byteToCharIndex(n.endByte)
                    if (start < end) {
                        ranges.add(
                            AnnotatedString.Range(
                                androidx.compose.ui.text.SpanStyle(background = Color(0x66FFEB3B)),
                                start,
                                end
                            )
                        )
                    }
                }
            }
        }
        if (t != null) {
            selectedNode?.let { id ->
                findNode(t.root, id)?.let { n ->
                    val start = text.byteToCharIndex(n.startByte)
                    val end = text.byteToCharIndex(n.endByte)
                    if (start < end) {
                        ranges.add(
                            AnnotatedString.Range(
                                androidx.compose.ui.text.SpanStyle(background = Color(0x668FC6FF)),
                                start,
                                end
                            )
                        )
                    }
                }
            }
        }
        AnnotatedString(text, spanStyles = ranges)
    }

    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        BasicTextField(
            value = TextFieldValue(annotated, editorValue.selection, editorValue.composition),
            onValueChange = { newValue ->
                editorValue = newValue
                onSourceChange(newValue.text)
            },
            modifier = Modifier.fillMaxSize().padding(6.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        )
    }
}

/** The collapsible syntax tree panel. */
@Composable
private fun TreePanel(
    tree: SyntaxTree?,
    source: String,
    matches: Set<Long>,
    showOnlyMatches: Boolean,
    expanded: Set<Long>,
    onToggle: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val root = tree?.root
    val displayRoot = if (root != null && showOnlyMatches && matches.isNotEmpty()) {
        filterTree(root, matches)
    } else {
        root
    }

    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        if (displayRoot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (showOnlyMatches) "No matches" else "No tree",
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            val rows = remember(displayRoot, expanded) {
                val out = mutableListOf<NodeRow>()
                collectRows(displayRoot, 0, expanded, out)
                out
            }
            LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                items(rows, key = { it.node.id }) { row ->
                    NodeRowView(row, source, expanded, onToggle)
                }
            }
        }
    }
}

private data class NodeRow(val node: SyntaxNode, val depth: Int, val hasChildren: Boolean)

private fun collectRows(
    node: SyntaxNode,
    depth: Int,
    expanded: Set<Long>,
    out: MutableList<NodeRow>
) {
    val hasChildren = node.children.isNotEmpty()
    out.add(NodeRow(node, depth, hasChildren))
    if (hasChildren && node.id in expanded) {
        node.children.forEach { collectRows(it, depth + 1, expanded, out) }
    }
}

/** Keep only nodes in [matches] plus their ancestors. */
private fun filterTree(root: SyntaxNode, matches: Set<Long>): SyntaxNode? {
    fun filter(n: SyntaxNode): SyntaxNode? {
        val keep = n.id in matches
        val kids = n.children.mapNotNull { filter(it) }
        return if (keep || kids.isNotEmpty()) n.copy(children = kids) else null
    }
    return filter(root)
}

private fun forEachNode(node: SyntaxNode, action: (SyntaxNode) -> Unit) {
    action(node)
    node.children.forEach { forEachNode(it, action) }
}

private fun findNode(node: SyntaxNode, id: Long): SyntaxNode? {
    if (node.id == id) return node
    node.children.forEach { findNode(it, id)?.let { found -> return found } }
    return null
}

@Composable
private fun NodeRowView(
    row: NodeRow,
    source: String,
    expanded: Set<Long>,
    onToggle: (Long) -> Unit
) {
    val node = row.node
    val textPreview = node.textOf(source).replace("\n", "\\n").take(40)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(node.id) }
            .padding(start = (row.depth * 14).dp, top = 1.dp, bottom = 1.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (row.hasChildren) {
                if (node.id in expanded) "▾" else "▸"
            } else {
                "·"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(14.dp)
        )
        Text(
            node.type,
            fontWeight = if (node.children.isEmpty()) FontWeight.Normal else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Text(
            " [${node.startByte}-${node.endByte}]",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline
        )
        if (textPreview.isNotEmpty()) {
            Text(
                "  \"$textPreview\"",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/** Convert a byte offset into a UTF-16 char index for highlighting. */
private fun String.byteToCharIndex(byte: Int): Int {
    val bytes = encodeToByteArray()
    if (byte >= bytes.size) return length
    return bytes.copyOfRange(0, byte.coerceAtLeast(0)).decodeToString().length
}

/** Lowercase string literals inside the query when case-insensitive. */
internal fun normalizePattern(pattern: String, caseSensitive: Boolean): String =
    if (caseSensitive) {
        pattern
    } else {
        pattern.replace(Regex("\"([^\"]*)\"")) { m ->
            "\"" + m.groupValues[1].lowercase() + "\""
        }
    }

/** Per-language starter source code. */
internal fun defaultSource(languageId: LanguageId): String = when (languageId) {
    LanguageId.JAVA -> """
        class HelloWorld {
            private final String name;

            public HelloWorld(String name) {
                this.name = name;
            }

            public static void main(String[] args) {
                System.out.println(new HelloWorld("world"));
            }
        }
    """.trimIndent()

    LanguageId.PYTHON -> """
        def fib(n):
            if n <= 1:
                return n
            return fib(n - 1) + fib(n - 2)

        print([fib(i) for i in range(10)])
    """.trimIndent()

    LanguageId.JAVASCRIPT -> """
        function greet(name) {
            return `Hello, ${'$'}{name}!`;
        }

        const names = ['world', 'kotlin'];
        for (const n of names) {
            console.log(greet(n));
        }
    """.trimIndent()

    LanguageId.RUST -> """
        fn fib(n: u64) -> u64 {
            match n {
                0 | 1 => n,
                _ => fib(n - 1) + fib(n - 2),
            }
        }

        fn main() {
            println!("{:?}", (1..=10).map(fib).collect::<Vec<_>>());
        }
    """.trimIndent()

    LanguageId.GO -> """
        package main

        import "fmt"

        func main() {
            nums := []int{1, 2, 3}
            for _, n := range nums {
                fmt.Println(n)
            }
        }
    """.trimIndent()

    LanguageId.JSON -> """
        {
          "name": "sample",
          "version": "1.0.0",
          "dependencies": {
            "kotlin": "^2.0.0"
          }
        }
    """.trimIndent()

    LanguageId.HTML -> """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <title>Sample</title>
        </head>
        <body>
          <h1>Hello</h1>
          <p>Sample content</p>
        </body>
        </html>
    """.trimIndent()

    LanguageId.TYPESCRIPT -> """
        interface Greeting {
          name: string;
        }

        function greet(g: Greeting): string {
          return `Hello, ${'$'}{g.name}!`;
        }
    """.trimIndent()

    LanguageId.C -> """
        #include <stdio.h>

        int main(void) {
            printf("hello, world\n");
            return 0;
        }
    """.trimIndent()

    LanguageId.CPP -> """
        #include <iostream>

        int main() {
            std::cout << "hello" << std::endl;
            return 0;
        }
    """.trimIndent()

    LanguageId.C_SHARP -> """
        using System;

        class Program
        {
            static void Main()
            {
                Console.WriteLine("hello");
            }
        }
    """.trimIndent()

    LanguageId.RUBY -> """
        def fib(n)
          return n if n <= 1
          fib(n - 1) + fib(n - 2)
        end

        puts (1..10).map { |i| fib(i) }.join(', ')
    """.trimIndent()

    LanguageId.PHP -> """
        <?php

        function greet(string ${'$'}name): string {
            return "Hello, ${'$'}name!";
        }

        echo greet("world") . "\n";
    """.trimIndent()

    LanguageId.BASH -> """
        #!/usr/bin/env bash

        set -euo pipefail

        for f in *.txt; do
          echo "Processing ${'$'}f"
        done
    """.trimIndent()

    LanguageId.CSS -> """
        body {
          margin: 0;
          padding: 1rem;
          background-color: #f0f0f0;
        }

        .card:hover {
          transform: scale(1.02);
        }
    """.trimIndent()

    LanguageId.JULIA -> """
        function fib(n::Int)
            n <= 1 && return n
            return fib(n - 1) + fib(n - 2)
        end

        println([fib(i) for i in 1:10])
    """.trimIndent()

    LanguageId.HASKELL -> """
        module Main where

        main :: IO ()
        main = mapM_ print [1 :: Int .. 10]
    """.trimIndent()

    LanguageId.SCALA -> """
        object Main extends App {
          val xs = (1 to 10).toList
          println(xs.mkString(", "))
        }
    """.trimIndent()

    LanguageId.OCAML -> """
        let rec fib n =
          if n <= 1 then n else fib (n - 1) + fib (n - 2)

        let () = List.iter (fun i -> Printf.printf "%d\n" (fib i)) [1; 2; 3; 4]
    """.trimIndent()

    LanguageId.REGEX -> """
        ^(https?://)?([a-z0-9.-]+)\.([a-z]{2,})(/\S*)?$
    """.trimIndent()

    LanguageId.AGDA -> """
        module Main where

        open import Data.Nat

        main : Nat
        main = suc (suc zero)
    """.trimIndent()

    LanguageId.VERILOG -> """
        module counter(
            input  wire clk,
            input  wire rst_n,
            output reg [7:0] count
        );

        always @(posedge clk or negedge rst_n) begin
            if (!rst_n)
                count <= 8'b0;
            else
                count <= count + 1;
        end

        endmodule
    """.trimIndent()

    LanguageId.EMBEDDED_TEMPLATE -> """
        <!DOCTYPE html>
        <html>
        <head>
          <title><%= title %></title>
        </head>
        <body>
          <% if (user) { %>
            <p>Hello, <%= user.name %></p>
          <% } %>
        </body>
        </html>
    """.trimIndent()

    LanguageId.TSX -> """
        import React, { useState } from 'react';

        export const Counter: React.FC<{ initial: number }> = ({ initial }) => {
          const [count, setCount] = useState(initial);
          return <button onClick={() => setCount(count + 1)}>{count}</button>;
        };
    """.trimIndent()
}
