package com.napps.filamentmanager.util

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Data model for a single step in the app tour.
 *
 * @property targetKey The unique ID of the UI element to highlight in this step.
 * @property description The message to display to the user during this step.
 * @property preAction Action to perform before this step is shown (e.g., expanding a card or scrolling).
 */
data class TourStep(
    val targetKey: String, // The ID of the UI element to highlight
    val description: String, // The message to display
    val preAction: suspend () -> Unit = {} // Action to perform before this step (e.g., expand a card)
)

/**
 * Modifier to mark a UI element as a tour target.
 * Captures the element's position and size in the root coordinate system and stores it in [targets].
 *
 * @param key Unique identifier for the tour target.
 * @param targets Mutable map where target coordinates are stored.
 */
fun Modifier.tourTarget(
    key: String,
    targets: MutableMap<String, Rect>
): Modifier = this.onGloballyPositioned { coordinates ->
    val rootPosition = coordinates.positionInRoot()
    targets[key] = Rect(rootPosition, coordinates.size.toSize())
}

/**
 * Composable that displays a full-screen overlay for the app tour.
 * It darkens the background and cuts a "spotlight" hole for the current target UI element.
 *
 * @param steps List of [TourStep]s defining the tour.
 * @param currentStepIndex The index of the currently active step.
 * @param targets A map of captured target bounds (from [tourTarget] modifier).
 * @param scrollState Optional [ScrollState] for auto-scrolling column layouts.
 * @param lazyListState Optional [LazyListState] for auto-scrolling lazy list layouts.
 * @param onNext Callback invoked when the user taps anywhere on the screen to advance the tour.
 */
@Composable
fun TourOverlay(
    steps: List<TourStep>,
    currentStepIndex: Int,
    targets: Map<String, Rect>,
    scrollState: ScrollState? = null,
    lazyListState: LazyListState? = null,
    onNext: () -> Unit
) {
    if (currentStepIndex !in steps.indices) return

    val step = steps[currentStepIndex]
    val targetRect = targets[step.targetKey]
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()

    // Execute preAction when step changes
    LaunchedEffect(currentStepIndex) {
        steps[currentStepIndex].preAction()
        // Wait for UI to settle (expansions, etc)
        delay(500)
    }

    // Auto-scroll logic if target is missing or off-screen
    LaunchedEffect(currentStepIndex, targetRect) {
        if (targetRect != null) {
            val padding = with(density) { 140.dp.toPx() } // Extra padding for bottom navigation
            
            val isOffBottom = targetRect.bottom > (screenHeight - padding)
            val isOffTop = targetRect.top < with(density) { 100.dp.toPx() } // Avoid being under TopAppBar

            if (isOffBottom || isOffTop) {
                val delta = if (isOffBottom) {
                    (targetRect.bottom - screenHeight) + padding
                } else {
                    targetRect.top - padding
                }
                
                // Faster animation (200ms) to reduce "laggy" feel
                val spec = tween<Float>(durationMillis = 200)
                scrollState?.animateScrollBy(delta, spec)
                lazyListState?.animateScrollBy(delta, spec)
            }
        }
    }

    // This captures the offset of the overlay itself relative to the root screen
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayOffset = coordinates.positionInRoot()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onNext
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.99f)) {
            // --- BACKGROUND COLOR ---
            drawRect(color = Color.Black.copy(alpha = 0.8f))

            targetRect?.let { rect ->
                // We subtract overlayOffset to convert Screen coordinates to Local coordinates
                val localLeft = rect.left - overlayOffset.x
                val localTop = rect.top - overlayOffset.y

                // --- SPOTLIGHT DRAWING ---
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(localLeft - 4.dp.toPx(), localTop - 4.dp.toPx()),
                    size = Size(rect.width + 8.dp.toPx(), rect.height + 8.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    blendMode = BlendMode.Clear
                )
            }
        }

        // Adjust text position coordinates
        val localLeft = (targetRect?.left ?: (screenWidth / 2)) - overlayOffset.x
        val localTop = (targetRect?.top ?: (screenHeight / 2)) - overlayOffset.y
        val localRight = (targetRect?.right ?: (screenWidth / 2)) - overlayOffset.x
        val localBottom = (targetRect?.bottom ?: (screenHeight / 2)) - overlayOffset.y

        val isAbove = localTop > (screenHeight / 2)
        
        // Calculate where there is more space: top or bottom
        val spaceAbove = localTop
        val spaceBelow = screenHeight - localBottom
        val useSpaceAbove = spaceAbove > spaceBelow

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Main description text
            Text(
                text = step.description,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .graphicsLayer {
                        val targetCenterX = (localLeft + localRight) / 2
                        val textWidthPx = 280.dp.toPx()
                        
                        translationX = (targetCenterX - textWidthPx / 2)
                            .coerceIn(0f, screenWidth - textWidthPx - 32.dp.toPx())
                        
                        translationY = if (targetRect == null) {
                            (screenHeight / 2) - 100.dp.toPx()
                        } else if (useSpaceAbove) {
                            localTop - 140.dp.toPx()
                        } else {
                            localBottom + 20.dp.toPx()
                        }
                    },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black, 
                        blurRadius = 12f, 
                        offset = Offset(2f, 2f)
                    )
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            // "Click anywhere to continue" note in the middle
            Text(
                text = "\"Click anywhere to continue\"",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Normal,
                    shadow = Shadow(
                        color = Color.Black,
                        blurRadius = 6f,
                        offset = Offset(1f, 1f)
                    )
                ),
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Global map of tour targets, if you prefer using a CompositionLocal instead of passing maps manually.
 */
val LocalTourTargets = compositionLocalOf<MutableMap<String, Rect>> { mutableMapOf() }
