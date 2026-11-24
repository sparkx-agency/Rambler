package com.voiceflow.ime

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceKeyboardScreen(
    listenState: ListenState,
    audioLevel: Float,
    showQwertyDrawer: Boolean,
    statusLabel: String,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onToggleQwerty: () -> Unit,
    onSwitchSystemKeyboard: () -> Unit,
    onCommitText: (String) -> Unit,
    onBackspace: () -> Unit,
    onUndoLastPhrase: () -> Unit,
    onEnterKey: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "geminiGlow")
    val shimmerShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (listenState == ListenState.LISTENING) 1400 else 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    val geminiBrush = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF4285F4),
            Color(0xFF9B72CB),
            Color(0xFFD96570),
            Color(0xFF38BDF8)
        ),
        startX = shimmerShift * 120f,
        endX = 360f + shimmerShift * 120f
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0E15))
            .navigationBarsPadding()
    ) {
        if (!showQwertyDrawer) {
            // Minimalist Bottom-Center Gemini Oval Tab Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                // Ambient Bottom Light Bleed
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .align(Alignment.BottomCenter)
                        .blur(26.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF4285F4).copy(alpha = if (listenState == ListenState.LISTENING) 0.45f else 0.18f)
                                )
                            )
                        )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT BUTTON: Tap for built-in QWERTY, Long-press for System Keyboard Switcher
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF161C29))
                            .border(1.dp, Color(0xFF283247), RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleQwerty()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwitchSystemKeyboard()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⌨", fontSize = 20.sp, color = Color(0xFFCBD5E1))
                    }

                    // CENTER OVAL PILL: Gemini Voice Mode Button
                    Box(contentAlignment = Alignment.Center) {
                        // Voice-reactive outer halo
                        val haloScale = if (listenState == ListenState.LISTENING) 1.06f + (audioLevel * 0.22f) else 1f
                        Box(
                            modifier = Modifier
                                .width(148.dp)
                                .height(56.dp)
                                .scale(haloScale)
                                .blur(14.dp)
                                .clip(CircleShape)
                                .background(geminiBrush)
                        )

                        // Interactive Glowing Pill
                        Box(
                            modifier = Modifier
                                .width(146.dp)
                                .height(56.dp)
                                .clip(CircleShape)
                                .background(geminiBrush)
                                .padding(2.dp)
                                .pointerInput(listenState) {
                                    detectTapGestures(
                                        onPress = {
                                            val startTime = System.currentTimeMillis()
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (listenState == ListenState.IDLE) {
                                                onStartListening()
                                                tryAwaitRelease()
                                                val held = System.currentTimeMillis() - startTime
                                                if (held > 380L) {
                                                    onStopListening()
                                                }
                                            } else if (listenState == ListenState.LISTENING) {
                                                onStopListening()
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(Color(0xFF101522).copy(alpha = 0.88f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (listenState) {
                                        ListenState.IDLE -> "🎙"
                                        ListenState.LISTENING -> "•••"
                                        ListenState.PROCESSING -> "✦"
                                    },
                                    fontSize = 20.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // RIGHT BUTTON: Tap = Backspace, Long-Press = Undo Entire Last Dictated Phrase
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF161C29))
                            .border(1.dp, Color(0xFF283247), RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onBackspace()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onUndoLastPhrase()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⌫", fontSize = 19.sp, color = Color(0xFFCBD5E1))
                    }
                }
            }
        } else {
            // Compact Built-in QWERTY Drawer
            CompactQwertyPanel(
                onBackToVoice = onToggleQwerty,
                onKeyPress = onCommitText,
                onBackspace = onBackspace,
                onEnter = onEnterKey
            )
        }
    }
}

@Composable
private fun CompactQwertyPanel(
    onBackToVoice: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    var shift by remember { mutableStateOf(false) }
    val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACK")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Top pill to return immediately to Gemini Voice Orb
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF3B82F6), CircleShape)
                    .clickable { onBackToVoice() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("✦ Voice Mode", color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { key ->
                    val weight = if (key == "SHIFT" || key == "BACK") 1.4f else 1f
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A2233))
                            .clickable {
                                when (key) {
                                    "SHIFT" -> shift = !shift
                                    "BACK" -> onBackspace()
                                    else -> {
                                        onKeyPress(if (shift) key.uppercase() else key)
                                        if (shift) shift = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val label = when (key) {
                            "SHIFT" -> if (shift) "⬆" else "⇧"
                            "BACK" -> "⌫"
                            else -> if (shift) key.uppercase() else key
                        }
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }

        // Bottom Space + Enter Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A2233))
                    .clickable { onKeyPress(",") },
                contentAlignment = Alignment.Center
            ) { Text(",", color = Color.White) }

            Box(
                modifier = Modifier
                    .weight(4f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .clickable { onKeyPress(" ") },
                contentAlignment = Alignment.Center
            ) { Text("space", color = Color(0xFF94A3B8), fontSize = 13.sp) }

            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A2233))
                    .clickable { onKeyPress(".") },
                contentAlignment = Alignment.Center
            ) { Text(".", color = Color.White) }

            Box(
                modifier = Modifier
                    .weight(1.5f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2563EB))
                    .clickable { onEnter() },
                contentAlignment = Alignment.Center
            ) { Text("↵", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}