package org.readerka.ui

import org.commonmark.node.Node
import org.commonmark.parser.Parser

object MarkdownDocument {
    private val parser = Parser.builder().build()
    fun parse(source: String): Node = parser.parse(source)
}
