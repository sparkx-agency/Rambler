package com.voiceflow.ime

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun VoiceKeyboardScreen(
    listenState: ListenState,
    audioLevel: Float,
    showQwertyDrawer: Boolean,
    statusLabel: String,
    canUndoLastPhrase: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelOrReset: () -> Unit,
    onToggleQwerty: () -> Unit,
    onSwitchSystemKeyboard: () -> Unit,
    onCommitText: (String) -> Unit,
    onBackspace: () -> Unit,
    onUndoLastPhrase: () -> Unit,
    onEnterKey: () -> Unit
) {
    val context = LocalContext.current

    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    // Smooth audio RMS through a critically-damped spring so the emblem breathes organically.
    val smoothedAudio by animateFloatAsState(
        targetValue = if (listenState == ListenState.LISTENING) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "smoothedAudio"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "voiceFlowAmbient")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (listenState) {
                    ListenState.LISTENING -> 1800
                    ListenState.PROCESSING -> 1100
                    ListenState.IDLE -> 4200
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerPhase"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val rimBrush = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF60A5FA),
            Color(0xFFA78BFA),
            Color(0xFFF472B6),
            Color(0xFF38BDF8)
        ),
        startX = shimmerPhase * 90f,
        endX = 320f + shimmerPhase * 90f
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF070A10).copy(alpha = 0.88f),
                        Color(0xFF070A10).copy(alpha = 0.96f)
                    )
                )
            )
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedContent(
            targetState = showQwertyDrawer,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                } else {
                    (fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.97f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                    )) togetherWith (fadeOut(tween(120)) + scaleOut(
                        targetScale = 0.97f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                    ))
                }
            },
            label = "drawerSwitch"
        ) { isQwerty ->
            if (!isQwerty) {
                VoiceFloatingDock(
                    listenState = listenState,
                    smoothedAudio = smoothedAudio,
                    wavePhase = if (reducedMotion) 0f else wavePhase,
                    rimBrush = rimBrush,
                    statusLabel = statusLabel,
                    canUndoLastPhrase = canUndoLastPhrase,
                    reducedMotion = reducedMotion,
                    onStartListening = onStartListening,
                    onStopListening = onStopListening,
                    onCancelOrReset = onCancelOrReset,
                    onToggleQwerty = onToggleQwerty,
                    onSwitchSystemKeyboard = onSwitchSystemKeyboard,
                    onUndoLastPhrase = onUndoLastPhrase,
                    onBackspace = onBackspace
                )
            } else {
                CompactQwertyPanel(
                    reducedMotion = reducedMotion,
                    onBackToVoice = onToggleQwerty,
                    onSwitchSystemKeyboard = onSwitchSystemKeyboard,
                    onKeyPress = onCommitText,
                    onBackspace = onBackspace,
                    onEnter = onEnterKey
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceFloatingDock(
    listenState: ListenState,
    smoothedAudio: Float,
    wavePhase: Float,
    rimBrush: Brush,
    statusLabel: String,
    canUndoLastPhrase: Boolean,
    reducedMotion: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelOrReset: () -> Unit,
    onToggleQwerty: () -> Unit,
    onSwitchSystemKeyboard: () -> Unit,
    onUndoLastPhrase: () -> Unit,
    onBackspace: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val currentListenState by rememberUpdatedState(listenState)
    val currentOnStartListening by rememberUpdatedState(onStartListening)
    val currentOnStopListening by rememberUpdatedState(onStopListening)
    val currentOnCancelOrReset by rememberUpdatedState(onCancelOrReset)
    val currentOnBackspace by rememberUpdatedState(onBackspace)

    var isPillPressed by remember { mutableStateOf(false) }
    var isBackspacePressed by remember { mutableStateOf(false) }
    val keyboardInteraction = remember { MutableInteractionSource() }
    val isKeyboardPressed by keyboardInteraction.collectIsPressedAsState()
    val undoInteraction = remember { MutableInteractionSource() }
    val isUndoPressed by undoInteraction.collectIsPressedAsState()

    LaunchedEffect(isBackspacePressed) {
        if (isBackspacePressed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnBackspace()
            delay(380L)
            while (isBackspacePressed) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnBackspace()
                delay(55L)
            }
        }
    }

    val pressSpring: AnimationSpec<Float> = if (reducedMotion) {
        tween(0)
    } else {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
    }

    val keyboardBtnScale by animateFloatAsState(
        targetValue = if (isKeyboardPressed) 0.90f else 1f,
        animationSpec = pressSpring,
        label = "keyboardBtnScale"
    )
    val backspaceBtnScale by animateFloatAsState(
        targetValue = if (isBackspacePressed) 0.90f else 1f,
        animationSpec = pressSpring,
        label = "backspaceBtnScale"
    )
    val pillPressScale by animateFloatAsState(
        targetValue = if (isPillPressed) 0.95f else 1f,
        animationSpec = pressSpring,
        label = "pillPressScale"
    )
    val undoScale by animateFloatAsState(
        targetValue = if (isUndoPressed) 0.92f else 1f,
        animationSpec = pressSpring,
        label = "undoScale"
    )

    val pillWidth by animateDpAsState(
        targetValue = when (listenState) {
            ListenState.IDLE -> 154.dp
            ListenState.LISTENING -> 174.dp
            ListenState.PROCESSING -> 160.dp
        },
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = 0.84f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pillWidth"
    )

    val rimAlpha by animateFloatAsState(
        targetValue = when (listenState) {
            ListenState.IDLE -> 0.45f
            ListenState.LISTENING -> 1.0f
            ListenState.PROCESSING -> 0.85f
        },
        animationSpec = tween(220),
        label = "rimAlpha"
    )

    val haloAlpha by animateFloatAsState(
        targetValue = when (listenState) {
            ListenState.IDLE -> 0.14f
            ListenState.LISTENING -> (0.48f + (smoothedAudio * 0.28f)).coerceIn(0f, 0.85f)
            ListenState.PROCESSING -> 0.30f
        },
        animationSpec = tween(220),
        label = "haloAlpha"
    )

    val dockSurface = Color(0xFF151924).copy(alpha = 0.92f)
    val dockTopCatchlight = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = 0.06f),
            Color.White.copy(alpha = 0.02f)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Subtle status & contextual undo strip above the dock.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (listenState != ListenState.IDLE) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (listenState == ListenState.LISTENING) Color(0xFF60A5FA) else Color(0xFFA78BFA)
                            )
                    )
                }
                Text(
                    text = statusLabel,
                    color = when (listenState) {
                        ListenState.LISTENING -> Color(0xFFBFDBFE)
                        ListenState.PROCESSING -> Color(0xFFDDD6FE)
                        ListenState.IDLE -> Color(0xFF94A3B8)
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.25.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(
                visible = canUndoLastPhrase && listenState == ListenState.IDLE,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Row(
                    modifier = Modifier
                        .scale(undoScale)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2636).copy(alpha = 0.92f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .combinedClickable(
                            interactionSource = undoInteraction,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onUndoLastPhrase()
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    UndoIcon(modifier = Modifier.size(12.dp), tint = Color(0xFF93C5FD))
                    Text(
                        text = "Undo",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Floating translucent obsidian glass capsule dock.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color.Black,
                    spotColor = Color(0xFF38BDF8).copy(alpha = 0.22f)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(dockSurface)
                .border(1.dp, dockTopCatchlight, RoundedCornerShape(28.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Utility Key: Toggle QWERTY (tap) / Switch IME (long press)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(keyboardBtnScale)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isKeyboardPressed) Color(0xFF283248) else Color(0xFF1C2333)
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isKeyboardPressed) 0.22f else 0.12f),
                                    Color.White.copy(alpha = 0.03f)
                                )
                            ),
                            RoundedCornerShape(16.dp)
                        )
                        .combinedClickable(
                            interactionSource = keyboardInteraction,
                            indication = null,
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
                    KeyboardGridIcon(
                        modifier = Modifier.size(22.dp),
                        tint = Color(0xFFE2E8F0)
                    )
                }

                // Center Hero Pill with bespoke VoiceFlow Logo
                Box(contentAlignment = Alignment.Center) {
                    val haloScale = if (listenState == ListenState.LISTENING) {
                        1.04f + (smoothedAudio * 0.20f)
                    } else {
                        1f
                    }
                    Box(
                        modifier = Modifier
                            .width(pillWidth + 6.dp)
                            .height(52.dp)
                            .scale(haloScale * pillPressScale)
                            .alpha(haloAlpha)
                            .blur(16.dp)
                            .clip(CircleShape)
                            .background(rimBrush)
                    )

                    Box(
                        modifier = Modifier
                            .width(pillWidth)
                            .height(50.dp)
                            .scale(pillPressScale)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF60A5FA).copy(alpha = rimAlpha),
                                        Color(0xFFA78BFA).copy(alpha = rimAlpha),
                                        Color(0xFFF472B6).copy(alpha = rimAlpha),
                                        Color(0xFF38BDF8).copy(alpha = rimAlpha)
                                    )
                                )
                            )
                            .padding(1.5.dp)
                            .pointerInput(Unit) {
                                val maxDragPx = with(density) { 84.dp.toPx() }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    isPillPressed = true
                                    val stateAtPress = currentListenState
                                    val pressStart = System.currentTimeMillis()
                                    var canceledByDragOut = false

                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    when (stateAtPress) {
                                        ListenState.IDLE -> currentOnStartListening()
                                        ListenState.LISTENING -> currentOnStopListening()
                                        ListenState.PROCESSING -> currentOnCancelOrReset()
                                    }

                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                change.consume()
                                                break
                                            }
                                            val dx = change.position.x - down.position.x
                                            val dy = change.position.y - down.position.y
                                            if (hypot(dx, dy) > maxDragPx) {
                                                canceledByDragOut = true
                                            }
                                        }
                                    } finally {
                                        isPillPressed = false
                                    }

                                    if (stateAtPress == ListenState.IDLE) {
                                        val heldMs = System.currentTimeMillis() - pressStart
                                        if (canceledByDragOut) {
                                            currentOnCancelOrReset()
                                        } else if (heldMs > 360L && currentListenState == ListenState.LISTENING) {
                                            currentOnStopListening()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            if (isPillPressed) Color(0xFF1A2236) else Color(0xFF131929),
                                            Color(0xFF0C101B)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (haloAlpha > 0.2f) {
                                Box(
                                    modifier = Modifier
                                        .width(82.dp)
                                        .height(26.dp)
                                        .blur(14.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF60A5FA).copy(alpha = haloAlpha * 0.35f))
                                )
                            }

                            VoiceFlowLogo(
                                modifier = Modifier.size(30.dp),
                                listenState = listenState,
                                audioLevel = smoothedAudio,
                                wavePhase = wavePhase
                            )
                        }
                    }
                }

                // Right Utility Key: Backspace (tap or hold-to-repeat)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(backspaceBtnScale)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isBackspacePressed) Color(0xFF283248) else Color(0xFF1C2333)
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isBackspacePressed) 0.22f else 0.12f),
                                    Color.White.copy(alpha = 0.03f)
                                )
                            ),
                            RoundedCornerShape(16.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isBackspacePressed = true
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        isBackspacePressed = false
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BackspaceIcon(
                        modifier = Modifier.size(21.dp),
                        tint = Color(0xFFE2E8F0)
                    )
                }
            }
        }
    }
}

/**
 * Bespoke VoiceFlow brand mark: sculpted studio capsule flanked by dual harmonic
 * acoustic wings and a fluid flow arc. Morphs smoothly with live audio level.
 */
@Composable
fun VoiceFlowLogo(
    modifier: Modifier = Modifier,
    listenState: ListenState = ListenState.IDLE,
    audioLevel: Float = 0f,
    wavePhase: Float = 0f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f

        val isListening = listenState == ListenState.LISTENING
        val isProcessing = listenState == ListenState.PROCESSING

        val energy = when {
            isListening -> (0.25f + audioLevel * 0.75f).coerceIn(0.25f, 1f)
            isProcessing -> 0.45f + 0.25f * sin(wavePhase)
            else -> 0f
        }

        val capsuleOsc = if (isListening) sin(wavePhase * 1.4f) * energy * h * 0.05f else 0f
        val innerWingExpand = if (isListening) energy * w * 0.035f else 0f
        val outerWingExpand = if (isListening) energy * w * 0.06f else 0f
        val outerWingAlpha = if (isListening) 0.72f + 0.28f * energy else 0.65f

        val capsuleBrush = Brush.verticalGradient(
            colors = if (isListening || isProcessing) {
                listOf(Color.White, Color(0xFF93C5FD), Color(0xFF60A5FA))
            } else {
                listOf(Color(0xFFF8FAFC), Color(0xFFBFDBFE))
            }
        )

        val wingBrush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF60A5FA),
                Color(0xFFA78BFA),
                Color(0xFF38BDF8)
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h)
        )

        val flowBrush = Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF60A5FA),
                Color(0xFFA78BFA),
                Color(0xFF38BDF8)
            )
        )

        val capsuleHalfHeight = h * 0.20f + capsuleOsc.coerceAtLeast(-h * 0.04f)
        val capsuleCy = h * 0.42f
        drawLine(
            brush = capsuleBrush,
            start = Offset(cx, capsuleCy - capsuleHalfHeight),
            end = Offset(cx, capsuleCy + capsuleHalfHeight),
            strokeWidth = w * 0.105f,
            cap = StrokeCap.Round
        )

        val innerStroke = w * 0.078f
        val leftInnerX = w * 0.305f - innerWingExpand
        val rightInnerX = w * 0.695f + innerWingExpand
        val innerTopY = h * 0.28f - energy * h * 0.03f
        val innerBotY = h * 0.56f + energy * h * 0.03f

        val leftInnerPath = Path().apply {
            moveTo(leftInnerX + w * 0.035f, innerTopY)
            cubicTo(
                leftInnerX - w * 0.055f, capsuleCy - h * 0.06f,
                leftInnerX - w * 0.055f, capsuleCy + h * 0.06f,
                leftInnerX + w * 0.035f, innerBotY
            )
        }
        drawPath(
            path = leftInnerPath,
            brush = wingBrush,
            style = Stroke(width = innerStroke, cap = StrokeCap.Round)
        )

        val rightInnerPath = Path().apply {
            moveTo(rightInnerX - w * 0.035f, innerTopY)
            cubicTo(
                rightInnerX + w * 0.055f, capsuleCy - h * 0.06f,
                rightInnerX + w * 0.055f, capsuleCy + h * 0.06f,
                rightInnerX - w * 0.035f, innerBotY
            )
        }
        drawPath(
            path = rightInnerPath,
            brush = wingBrush,
            style = Stroke(width = innerStroke, cap = StrokeCap.Round)
        )

        val outerStroke = w * 0.065f
        val leftOuterX = w * 0.165f - outerWingExpand
        val rightOuterX = w * 0.835f + outerWingExpand
        val outerTopY = h * 0.33f - energy * h * 0.04f
        val outerBotY = h * 0.51f + energy * h * 0.04f

        val leftOuterPath = Path().apply {
            moveTo(leftOuterX + w * 0.025f, outerTopY)
            cubicTo(
                leftOuterX - w * 0.045f, capsuleCy - h * 0.04f,
                leftOuterX - w * 0.045f, capsuleCy + h * 0.04f,
                leftOuterX + w * 0.025f, outerBotY
            )
        }
        drawPath(
            path = leftOuterPath,
            color = Color(0xFF60A5FA).copy(alpha = outerWingAlpha),
            style = Stroke(width = outerStroke, cap = StrokeCap.Round)
        )

        val rightOuterPath = Path().apply {
            moveTo(rightOuterX - w * 0.025f, outerTopY)
            cubicTo(
                rightOuterX + w * 0.045f, capsuleCy - h * 0.04f,
                rightOuterX + w * 0.045f, capsuleCy + h * 0.04f,
                rightOuterX - w * 0.025f, outerBotY
            )
        }
        drawPath(
            path = rightOuterPath,
            color = Color(0xFF38BDF8).copy(alpha = outerWingAlpha),
            style = Stroke(width = outerStroke, cap = StrokeCap.Round)
        )

        val flowY = h * 0.77f
        val flowDip = h * 0.11f + energy * h * 0.025f * sin(wavePhase + 0.8f)
        val flowPath = Path().apply {
            moveTo(w * 0.25f, flowY)
            cubicTo(
                w * 0.38f, flowY + flowDip,
                w * 0.62f, flowY + flowDip,
                w * 0.75f, flowY
            )
        }
        drawPath(
            path = flowPath,
            brush = flowBrush,
            style = Stroke(width = w * 0.082f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun KeyboardGridIcon(
    modifier: Modifier = Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.075f

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.20f),
            size = Size(w * 0.84f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
            style = Stroke(width = stroke, join = StrokeJoin.Round)
        )

        val dotR = w * 0.045f
        val row1Y = h * 0.36f
        val row2Y = h * 0.50f
        for (i in 0..3) {
            val x = w * (0.24f + i * 0.173f)
            drawCircle(color = tint, radius = dotR, center = Offset(x, row1Y))
        }
        for (i in 0..2) {
            val x = w * (0.32f + i * 0.18f)
            drawCircle(color = tint, radius = dotR, center = Offset(x, row2Y))
        }

        drawLine(
            color = tint,
            start = Offset(w * 0.30f, h * 0.65f),
            end = Offset(w * 0.70f, h * 0.65f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun BackspaceIcon(
    modifier: Modifier = Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.082f

        val tagPath = Path().apply {
            moveTo(w * 0.34f, h * 0.22f)
            lineTo(w * 0.84f, h * 0.22f)
            cubicTo(w * 0.90f, h * 0.22f, w * 0.92f, h * 0.25f, w * 0.92f, h * 0.31f)
            lineTo(w * 0.92f, h * 0.69f)
            cubicTo(w * 0.92f, h * 0.75f, w * 0.90f, h * 0.78f, w * 0.84f, h * 0.78f)
            lineTo(w * 0.34f, h * 0.78f)
            lineTo(w * 0.10f, h * 0.50f)
            close()
        }
        drawPath(
            path = tagPath,
            color = tint,
            style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )

        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.39f),
            end = Offset(w * 0.72f, h * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.72f, h * 0.39f),
            end = Offset(w * 0.50f, h * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun GlobeIcon(
    modifier: Modifier = Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val r = w * 0.40f
        val center = Offset(w * 0.5f, h * 0.5f)

        drawCircle(
            color = tint,
            radius = r,
            center = center,
            style = Stroke(width = stroke)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.5f),
            end = Offset(w * 0.90f, h * 0.5f),
            strokeWidth = stroke * 0.85f
        )
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.33f, h * 0.10f),
            size = Size(w * 0.34f, h * 0.80f),
            style = Stroke(width = stroke * 0.85f)
        )
    }
}

@Composable
private fun UndoIcon(
    modifier: Modifier = Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f

        val arcPath = Path().apply {
            moveTo(w * 0.20f, h * 0.42f)
            lineTo(w * 0.58f, h * 0.42f)
            cubicTo(w * 0.82f, h * 0.42f, w * 0.86f, h * 0.78f, w * 0.56f, h * 0.78f)
            lineTo(w * 0.32f, h * 0.78f)
        }
        drawPath(
            path = arcPath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        val arrowPath = Path().apply {
            moveTo(w * 0.34f, h * 0.24f)
            lineTo(w * 0.16f, h * 0.42f)
            lineTo(w * 0.34f, h * 0.60f)
        }
        drawPath(
            path = arrowPath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun ShiftIcon(
    modifier: Modifier = Modifier,
    active: Boolean,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.095f
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.54f)
            lineTo(w * 0.66f, h * 0.54f)
            lineTo(w * 0.66f, h * 0.84f)
            lineTo(w * 0.34f, h * 0.84f)
            lineTo(w * 0.34f, h * 0.54f)
            lineTo(w * 0.14f, h * 0.54f)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = if (active) Fill else Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun ReturnIcon(
    modifier: Modifier = Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        val linePath = Path().apply {
            moveTo(w * 0.80f, h * 0.24f)
            lineTo(w * 0.80f, h * 0.62f)
            lineTo(w * 0.22f, h * 0.62f)
        }
        drawPath(
            path = linePath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        val tipPath = Path().apply {
            moveTo(w * 0.38f, h * 0.44f)
            lineTo(w * 0.20f, h * 0.62f)
            lineTo(w * 0.38f, h * 0.80f)
        }
        drawPath(
            path = tipPath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun CompactQwertyPanel(
    reducedMotion: Boolean,
    onBackToVoice: () -> Unit,
    onSwitchSystemKeyboard: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var shift by remember { mutableStateOf(false) }

    val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACK")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 620.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeycapPill(
                reducedMotion = reducedMotion,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBackToVoice()
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VoiceFlowLogo(modifier = Modifier.size(16.dp))
                    Text(
                        text = "Voice",
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    )
                }
            }

            KeycapPill(
                reducedMotion = reducedMotion,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSwitchSystemKeyboard()
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    GlobeIcon(modifier = Modifier.size(14.dp), tint = Color(0xFF94A3B8))
                    Text(
                        text = "Keyboards",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row.forEach { key ->
                    val weight = if (key == "SHIFT" || key == "BACK") 1.45f else 1f
                    val isModifierKey = key == "SHIFT" || key == "BACK"
                    QwertyKeycap(
                        modifier = Modifier
                            .weight(weight)
                            .height(44.dp),
                        containerColor = when {
                            key == "SHIFT" && shift -> Color(0xFF2563EB)
                            isModifierKey -> Color(0xFF171E2E)
                            else -> Color(0xFF21293C)
                        },
                        pressedColor = Color(0xFF334155),
                        reducedMotion = reducedMotion,
                        onPressDown = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            when (key) {
                                "SHIFT" -> shift = !shift
                                "BACK" -> onBackspace()
                                else -> {
                                    onKeyPress(if (shift) key.uppercase() else key)
                                    if (shift) shift = false
                                }
                            }
                        }
                    ) {
                        when (key) {
                            "SHIFT" -> ShiftIcon(
                                modifier = Modifier.size(17.dp),
                                active = shift,
                                tint = Color.White
                            )
                            "BACK" -> BackspaceIcon(
                                modifier = Modifier.size(19.dp),
                                tint = Color(0xFFE2E8F0)
                            )
                            else -> Text(
                                text = if (shift) key.uppercase() else key,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QwertyKeycap(
                modifier = Modifier.weight(1.2f).height(44.dp),
                containerColor = Color(0xFF171E2E),
                pressedColor = Color(0xFF283248),
                reducedMotion = reducedMotion,
                onPressDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKeyPress(",")
                }
            ) {
                Text(",", color = Color(0xFFE2E8F0), fontSize = 16.sp)
            }

            QwertyKeycap(
                modifier = Modifier.weight(4f).height(44.dp),
                containerColor = Color(0xFF21293C),
                pressedColor = Color(0xFF334155),
                reducedMotion = reducedMotion,
                onPressDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKeyPress(" ")
                }
            ) {
                Text(
                    text = "space",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    letterSpacing = 0.25.sp
                )
            }

            QwertyKeycap(
                modifier = Modifier.weight(1.2f).height(44.dp),
                containerColor = Color(0xFF171E2E),
                pressedColor = Color(0xFF283248),
                reducedMotion = reducedMotion,
                onPressDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKeyPress(".")
                }
            ) {
                Text(".", color = Color(0xFFE2E8F0), fontSize = 16.sp)
            }

            QwertyKeycap(
                modifier = Modifier.weight(1.5f).height(44.dp),
                containerColor = Color(0xFF2563EB),
                pressedColor = Color(0xFF3B82F6),
                reducedMotion = reducedMotion,
                onPressDown = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEnter()
                }
            ) {
                ReturnIcon(modifier = Modifier.size(18.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun QwertyKeycap(
    modifier: Modifier = Modifier,
    containerColor: Color,
    pressedColor: Color,
    reducedMotion: Boolean,
    onPressDown: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentOnPressDown by rememberUpdatedState(onPressDown)
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "keycapScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isPressed) pressedColor else containerColor)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isPressed) 0.20f else 0.11f),
                        Color.White.copy(alpha = 0.02f)
                    )
                ),
                shape = RoundedCornerShape(10.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        currentOnPressDown()
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeycapPill(
    reducedMotion: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pillHeaderScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .background(if (pressed) Color(0xFF283248) else Color(0xFF1A2233))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
