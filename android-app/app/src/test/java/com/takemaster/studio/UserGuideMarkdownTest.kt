package com.takemaster.studio

import com.takemaster.studio.ui.UserGuideMarkdown
import com.takemaster.studio.ui.UserGuideMarkdown.GuideBlock
import com.takemaster.studio.ui.UserGuideMarkdown.InlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserGuideMarkdownTest {

    @Test
    fun bundledUserGuideAsset_parsesAllSectionsTablesAndAnchors() {
        val guideFile = listOf(
            File("app/src/main/assets/user_guide.md"),
            File("src/main/assets/user_guide.md")
        ).first { it.exists() }

        val blocks = UserGuideMarkdown.parseBlocks(guideFile.readText())
        assertTrue(blocks.first() is GuideBlock.Title)

        val sections = blocks.filterIsInstance<GuideBlock.Section>()
        val anchors = sections.map { it.anchor }.toSet()
        // Table of Contents + Sections 1..8
        assertTrue(sections.size >= 9)

        val tocLinks = blocks.filterIsInstance<GuideBlock.ListItem>().mapNotNull { it.anchorTarget }
        assertEquals(8, tocLinks.size)
        tocLinks.forEach { target ->
            assertTrue("Expected section anchor '$target' in $anchors", target in anchors)
        }

        val tables = blocks.filterIsInstance<GuideBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(listOf("Aspect", "**Comp**", "**Compare**"), tables.first().headers)
        assertTrue(tables.first().rows.size >= 6)

        val joinedText = blocks.joinToString("\n") { block ->
            when (block) {
                is GuideBlock.Paragraph -> block.text
                is GuideBlock.ListItem -> block.text
                else -> ""
            }
        }
        assertTrue(joinedText.contains("Pan"))
        assertTrue(joinedText.contains("User Guide"))
        assertFalse(joinedText.contains("Optional macOS M2 Companion CLI"))
    }

    @Test
    fun parseInlineTokens_extractsBoldCodeItalicAndLinksCleanly() {
        val tokens = UserGuideMarkdown.parseInlineTokens(
            "Tap **Pan** or `Split Tap` in *Comp* mode ([Quick Start](#1-quick-start))."
        )
        assertTrue(tokens.any { it.style == InlineStyle.BOLD && it.text == "Pan" })
        assertTrue(tokens.any { it.style == InlineStyle.CODE && it.text == "Split Tap" })
        assertTrue(tokens.any { it.style == InlineStyle.ITALIC && it.text == "Comp" })
        assertTrue(tokens.any { it.style == InlineStyle.LINK && it.text == "Quick Start" })
    }
}
