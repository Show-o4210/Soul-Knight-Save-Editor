package com.example.soul_knight_save_editor.unlock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.example.soul_knight_save_editor.R

@Composable internal fun AnimatedHeader(mode: AssistantMode?, page: AssistantPage, busy: Boolean, compact: Boolean,
    onTitleClick: () -> Unit, onGuide: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    val height by animateDpAsState(if (compact) 48.dp else 72.dp, spring(stiffness = 500f), label = "header-height")
    val logo by animateDpAsState(if (compact) 24.dp else if (mode != null) 32.dp else 44.dp, label = "header-logo")
    val font by animateFloatAsState(if (compact) 16f else if (mode != null) 18f else 22f, label = "header-title")
    val modeText = mode?.label.orEmpty()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
    Surface(onClick = onTitleClick, modifier = Modifier.weight(1f).height(height).testTag("app-header"),
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .65f), contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(logo), shape = RoundedCornerShape(if (compact) 7.dp else 12.dp), color = Color(0xFFE4EEDC)) {
                Image(painterResource(R.drawable.ic_showcheer), contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(if (compact) 2.dp else 3.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("骑士档案馆", fontSize = font.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (compact && mode != null) Text(" · $modeText", fontSize = 10.sp, maxLines = 1)
                }
                AnimatedVisibility(!compact && mode != null) { Text(modeText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (mode != null) {
                if (page == AssistantPage.WORKSPACE) {
                    TextButton(onGuide, enabled = !busy, modifier = Modifier.width(46.dp), contentPadding = PaddingValues(2.dp)) { Text("模式", fontSize = 12.sp) }
                    TextButton(onSettings, enabled = !busy, modifier = Modifier.width(46.dp), contentPadding = PaddingValues(2.dp)) { Text("设置", fontSize = 12.sp) }
                } else TextButton(onBack, enabled = !busy, modifier = Modifier.width(46.dp), contentPadding = PaddingValues(2.dp)) { Text("返回", fontSize = 12.sp) }
            }
        }
    }
    CommunityMenu(busy, compact, height)
    }
}

private data class CommunityLink(val label: String, val url: String, val icon: Int)

private val communityLinks = listOf(
    CommunityLink("GitHub 仓库", "https://github.com/Show-o4210/Soul-Knight-Save-Editor", R.drawable.ic_code),
    CommunityLink("QQ 交流群", "https://qm.qq.com/q/9Sg9mdgarY", R.drawable.ic_group),
    CommunityLink("赞助项目", "https://showcheer.top/sponsor/", R.drawable.ic_heart_outline)
)

@Composable private fun CommunityMenu(busy: Boolean, compact: Boolean, height: Dp) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }, enabled = !busy,
            modifier = Modifier.width(76.dp).height(height).testTag("join-us"),
            shape = RoundedCornerShape(if (compact) 20.dp else 24.dp), contentPadding = PaddingValues(4.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(painterResource(R.drawable.ic_group), contentDescription = null, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
                Text("加入我们", fontSize = 12.sp, maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            communityLinks.forEach { link ->
                DropdownMenuItem(
                    text = { Text(link.label) },
                    leadingIcon = { Icon(painterResource(link.icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    enabled = !busy,
                    onClick = {
                        expanded = false
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "无法打开链接，请安装浏览器后重试。", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }
}

/** Compact glass-style rail: taps and horizontal swipes select; chevrons are visual hints only. */
@Composable internal fun WorkspaceTabBar(state: AssistantState, tabs: List<WorkspaceTab>, onSelect: (WorkspaceTab) -> Unit) {
    require(tabs.isNotEmpty() && tabs.map { it.id }.distinct().size == tabs.size)
    val density = LocalDensity.current
    val threshold = with(density) { 40.dp.toPx() }
    val index = tabs.indexOfFirst { it.id == state.workspaceTab }.coerceAtLeast(0)
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth(), shape = RoundedCornerShape(50.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)), shadowElevation = 6.dp) {
            Row(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .02f))))
                .padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", Modifier.width(16.dp).semantics { contentDescription = "可向右滑动切换页面" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BoxWithConstraints(Modifier.weight(1f)) {
                    val width = (maxWidth / tabs.size).coerceAtLeast(64.dp)
                    val scroll = rememberScrollState()
                    val offset by animateDpAsState(width * index, spring(stiffness = 500f), label = "tab-indicator")
                    LaunchedEffect(index, width) {
                        val left = with(density) { (width * index).toPx() }
                        val viewport = with(density) { maxWidth.toPx() }
                        scroll.animateScrollTo((left - (viewport - with(density) { width.toPx() }) / 2).toInt().coerceAtLeast(0))
                    }
                    Box(Modifier.horizontalScroll(scroll).height(48.dp).pointerInput(index, tabs, state.busy) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var dx = 0f
                            var dy = 0f
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                dx += change.position.x - change.previousPosition.x
                                dy += change.position.y - change.previousPosition.y
                            } while (event.changes.any { it.id == down.id && it.pressed })
                            if (!state.busy && abs(dx) >= threshold && abs(dx) > abs(dy) * 1.5f)
                                onSelect(tabs[(index + if (dx < 0) 1 else -1).coerceIn(tabs.indices)])
                        }
                    }) {
                        Box(Modifier.width(width * tabs.size).height(48.dp)) {
                            Surface(Modifier.offset(x = offset).width(width).fillMaxHeight(), shape = RoundedCornerShape(50.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = .82f), border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))) {}
                            Row {
                                tabs.forEach { tab ->
                                    val selected = state.workspaceTab == tab.id
                                    Surface(onClick = { onSelect(tab) }, enabled = !state.busy,
                                        modifier = Modifier.width(width).height(48.dp).testTag("tab-${tab.id}").semantics { this.selected = selected; role = Role.Tab },
                                        shape = RoundedCornerShape(50.dp), color = Color.Transparent,
                                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                                        Box(contentAlignment = Alignment.Center) { Text(tab.label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
                                    }
                                }
                            }
                        }
                    }
                }
                Text("›", Modifier.width(16.dp).semantics { contentDescription = "可向左滑动切换页面" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
