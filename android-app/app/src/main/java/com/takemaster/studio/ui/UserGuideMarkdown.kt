package com.takemaster.studio.ui

import java.util.Locale

/**
 * Lightweight pure-Kotlin parser for the bundled Take Master Markdown user guide.
 * Handles headings, horizontal rules, blockquotes, ordered/unordered lists (up to 2 levels),
 * pipe tables, and inline bold/code/italic/link spans.
 */
object UserGuideMarkdown {

    enum class InlineStyle {
        PLAIN,
        BOLD,
        CODE,
        ITALIC,
        LINK
    }

    data class InlineToken(
        val text: String,
        val style: InlineStyle
    )

    sealed interface GuideBlock {
        data class Title(val text: String) : GuideBlock
        data class Section(val anchor: String, val text: String) : GuideBlock
        data class SubSection(val text: String) : GuideBlock
        data class Paragraph(val text: String) : GuideBlock
        data class Quote(val text: String) : GuideBlock
        data class ListItem(
            val marker: String,
            val text: String,
            val indentLevel: Int = 0,
            val anchorTarget: String? = null
        ) : GuideBlock
        data class Table(
            val headers: List<String>,
            val rows: List<List<String>>
        ) : GuideBlock
        data object Divider : GuideBlock
    }

    fun headingAnchor(heading: String): String =
        heading.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(' ', '-')

    fun parseBlocks(markdown: String): List<GuideBlock> {
        val lines = markdown.lines()
        val blocks = mutableListOf<GuideBlock>()
        val paraBuf = mutableListOf<String>()

        fun flushParagraph() {
            if (paraBuf.isNotEmpty()) {
                blocks.add(GuideBlock.Paragraph(paraBuf.joinToString(" ")))
                paraBuf.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            when {
                trimmed.isEmpty() -> {
                    flushParagraph()
                    i++
                }
                trimmed == "---" || trimmed == "***" -> {
                    flushParagraph()
                    blocks.add(GuideBlock.Divider)
                    i++
                }
                trimmed.startsWith("# ") -> {
                    flushParagraph()
                    blocks.add(GuideBlock.Title(trimmed.removePrefix("# ").trim()))
                    i++
                }
                trimmed.startsWith("## ") -> {
                    flushParagraph()
                    val heading = trimmed.removePrefix("## ").trim()
                    blocks.add(GuideBlock.Section(anchor = headingAnchor(heading), text = heading))
                    i++
                }
                trimmed.startsWith("### ") -> {
                    flushParagraph()
                    blocks.add(GuideBlock.SubSection(trimmed.removePrefix("### ").trim()))
                    i++
                }
                trimmed.startsWith("> ") -> {
                    flushParagraph()
                    val quoteLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quoteLines.add(lines[i].trim().removePrefix(">").trim())
                        i++
                    }
                    blocks.add(GuideBlock.Quote(quoteLines.joinToString(" ")))
                }
                trimmed.startsWith("|") -> {
                    flushParagraph()
                    val tableLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        tableLines.add(lines[i].trim())
                        i++
                    }
                    val parsed = parseTable(tableLines)
                    if (parsed != null) {
                        blocks.add(parsed)
                    }
                }
                else -> {
                    val listMatch = LIST_ITEM_REGEX.matchEntire(raw)
                    if (listMatch != null) {
                        flushParagraph()
                        val indentSpaces = listMatch.groupValues[1].length
                        val markerRaw = listMatch.groupValues[2]
                        val body = listMatch.groupValues[3].trim()
                        val marker = if (markerRaw == "-" || markerRaw == "*") "•" else markerRaw
                        val linkMatch = SINGLE_ANCHOR_LINK_REGEX.matchEntire(body)
                        val displayBody = linkMatch?.groupValues?.get(1) ?: body
                        val anchorTarget = linkMatch?.groupValues?.get(2)
                        blocks.add(
                            GuideBlock.ListItem(
                                marker = marker,
                                text = displayBody,
                                indentLevel = if (indentSpaces >= 2) 1 else 0,
                                anchorTarget = anchorTarget
                            )
                        )
                        i++
                    } else {
                        paraBuf.add(trimmed)
                        i++
                    }
                }
            }
        }
        flushParagraph()
        return blocks
    }

    fun parseInlineTokens(text: String): List<InlineToken> {
        val tokens = mutableListOf<InlineToken>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                tokens.add(InlineToken(plain.toString(), InlineStyle.PLAIN))
                plain.clear()
            }
        }

        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flushPlain()
                        tokens.add(InlineToken(text.substring(i + 2, end), InlineStyle.BOLD))
                        i = end + 2
                        continue
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flushPlain()
                        tokens.add(InlineToken(text.substring(i + 1, end), InlineStyle.CODE))
                        i = end + 1
                        continue
                    }
                }
                text[i] == '[' -> {
                    val closeBracket = text.indexOf("](#", i + 1)
                    if (closeBracket > i + 1) {
                        val closeParen = text.indexOf(')', closeBracket + 3)
                        if (closeParen > closeBracket + 3) {
                            flushPlain()
                            tokens.add(InlineToken(text.substring(i + 1, closeBracket), InlineStyle.LINK))
                            i = closeParen + 1
                            continue
                        }
                    }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && text[i + 1] != ' ') {
                        flushPlain()
                        tokens.add(InlineToken(text.substring(i + 1, end), InlineStyle.ITALIC))
                        i = end + 1
                        continue
                    }
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return tokens
    }

    private fun parseTable(lines: List<String>): GuideBlock.Table? {
        if (lines.size < 2) return null
        fun splitCells(row: String): List<String> =
            row.trim().trim('|').split('|').map { it.trim() }

        val headers = splitCells(lines.first())
        val isSeparator = lines[1].trim().trim('|').split('|').all { cell ->
            cell.trim().matches(Regex(":?-{2,}:?"))
        }
        val bodyStart = if (isSeparator) 2 else 1
        val rows = lines.drop(bodyStart).map(::splitCells)
        return GuideBlock.Table(headers = headers, rows = rows)
    }

    private val LIST_ITEM_REGEX = Regex("""^(\s*)(-|\*|\d+\.)\s+(.+)$""")
    private val SINGLE_ANCHOR_LINK_REGEX = Regex("""^\[([^\]]+)]\(#([^)]+)\)$""")
}
