package com.takemaster.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.takemaster.studio.ui.UserGuideMarkdown.GuideBlock
import com.takemaster.studio.ui.UserGuideMarkdown.InlineStyle
import kotlinx.coroutines.launch

@Composable
fun UserGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blocks = remember {
        val raw = runCatching {
            context.assets.open("user_guide.md").bufferedReader().use { it.readText() }
        }.getOrElse {
            "# Take Master — User Guide\n\nUnable to load user guide asset."
        }
        UserGuideMarkdown.parseBlocks(raw)
    }

    val sections = remember(blocks) {
        blocks.mapIndexedNotNull { idx, block ->
            if (block is GuideBlock.Section && !block.text.equals("Table of Contents", ignoreCase = true)) {
                Triple(idx, block.anchor, block.text)
            } else {
                null
            }
        }
    }
    val anchorToIndex = remember(blocks) {
        buildMap {
            blocks.forEachIndexed { idx, block ->
                if (block is GuideBlock.Section) {
                    put(block.anchor, idx)
                }
            }
        }
    }

    val listState = rememberLazyListState()

    fun scrollToAnchor(anchor: String) {
        val targetIdx = anchorToIndex[anchor] ?: return
        scope.launch {
            listState.animateScrollToItem(targetIdx)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121826))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Take Master User Guide",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close User Guide",
                            tint = Color(0xFFCBD5E1)
                        )
                    }
                }

                // Section Quick-Jump Bar
                if (sections.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B111E))
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sections.forEach { (blockIdx, _, title) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E293B))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch { listState.animateScrollToItem(blockIdx) }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = title,
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(blocks) { block ->
                        GuideBlockView(
                            block = block,
                            onNavigateAnchor = ::scrollToAnchor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideBlockView(
    block: GuideBlock,
    onNavigateAnchor: (String) -> Unit
) {
    when (block) {
        is GuideBlock.Title -> {
            Text(
                text = block.text,
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        is GuideBlock.Section -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = block.text,
                    color = Color(0xFF38BDF8),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                HorizontalDivider(color = Color(0xFF1E3A8A))
            }
        }
        is GuideBlock.SubSection -> {
            Text(
                text = block.text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        is GuideBlock.Paragraph -> {
            Text(
                text = buildStyledText(block.text),
                color = Color(0xFFCBD5E1),
                fontSize = 13.5.sp,
                lineHeight = 20.sp
            )
        }
        is GuideBlock.Quote -> {
            Surface(
                color = Color(0xFF1E3A8A).copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = buildStyledText(block.text),
                    color = Color(0xFFDBEAFE),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        is GuideBlock.ListItem -> {
            val anchor = block.anchorTarget
            val rowModifier = Modifier
                .fillMaxWidth()
                .padding(start = (block.indentLevel * 16).dp)
                .then(
                    if (anchor != null) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigateAnchor(anchor) }
                            .padding(vertical = 2.dp)
                    } else {
                        Modifier
                    }
                )
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = block.marker,
                    color = if (anchor != null) Color(0xFF38BDF8) else Color(0xFF10B981),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 18.dp)
                )
                Text(
                    text = if (anchor != null) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF60A5FA), fontWeight = FontWeight.SemiBold)) {
                                append(block.text)
                            }
                        }
                    } else {
                        buildStyledText(block.text)
                    },
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is GuideBlock.Table -> {
            Surface(
                color = Color(0xFF121826),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        block.headers.forEachIndexed { idx, cell ->
                            Text(
                                text = buildStyledText(cell),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(if (idx == 0) 0.75f else 1f)
                            )
                        }
                    }
                    block.rows.forEachIndexed { rowIdx, row ->
                        HorizontalDivider(color = Color(0xFF1E293B))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (rowIdx % 2 == 0) Color(0xFF0F172A) else Color(0xFF121826)
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            block.headers.indices.forEach { colIdx ->
                                val cell = row.getOrNull(colIdx).orEmpty()
                                Text(
                                    text = buildStyledText(cell),
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.weight(if (colIdx == 0) 0.75f else 1f)
                                )
                            }
                        }
                    }
                }
            }
        }
        GuideBlock.Divider -> {
            HorizontalDivider(
                color = Color(0xFF1E293B),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

private fun buildStyledText(text: String): AnnotatedString = buildAnnotatedString {
    UserGuideMarkdown.parseInlineTokens(text).forEach { token ->
        when (token.style) {
            InlineStyle.PLAIN -> append(token.text)
            InlineStyle.BOLD -> withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                append(token.text)
            }
            InlineStyle.CODE -> withStyle(
                SpanStyle(
                    color = Color(0xFF7DD3FC),
                    background = Color(0xFF1E293B),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(token.text)
            }
            InlineStyle.ITALIC -> withStyle(SpanStyle(color = Color(0xFF94A3B8), fontStyle = FontStyle.Italic)) {
                append(token.text)
            }
            InlineStyle.LINK -> withStyle(SpanStyle(color = Color(0xFF60A5FA), fontWeight = FontWeight.SemiBold)) {
                append(token.text)
            }
        }
    }
}
