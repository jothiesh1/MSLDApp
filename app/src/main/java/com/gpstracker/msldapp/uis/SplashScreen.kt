package com.gpstracker.msldapp.uis

import android.graphics.BlurMaskFilter
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpstracker.msldapp.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(
    var pos: Offset,
    var vel: Offset,
    var alpha: Float,
    var radius: Float
)

private data class SpeedLine(
    var pos: Offset,
    var length: Float,
    var speed: Float,
    var alpha: Float
)

@Composable
fun SplashScreen(onSplashComplete: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val particles = remember { mutableStateListOf<Particle>() }
    val speedLines = remember { mutableStateListOf<SpeedLine>() }
    val density = LocalDensity.current.density

    // Thinture brand green color
    val thintureGreen = Color(0xFF33A638)
    val lightGreen = Color(0xFF4CAF50)
    val darkGreen = Color(0xFF2E7D32)

    // Logo 3D zoom animation
    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "logo_zoom"
    )

    val logoRotationX by animateFloatAsState(
        targetValue = if (startAnimation) 0f else -90f,
        animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
        label = "logo_rotation_x"
    )

    val logoRotationY by animateFloatAsState(
        targetValue = if (startAnimation) 0f else 180f,
        animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
        label = "logo_rotation_y"
    )

    val logoAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = LinearEasing),
        label = "logo_alpha"
    )

    // Continuous rotation after initial zoom
    val logoSpinY by rememberInfiniteTransition(label = "logo_spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logo_spin_y"
    )

    val logoPulse by rememberInfiniteTransition(label = "logo_pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_pulse"
    )

    val logoGlow by rememberInfiniteTransition(label = "logo_glow").animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_glow_value"
    )

    // Title animations
    val titleScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.5f,
        animationSpec = tween(durationMillis = 1500, delayMillis = 1400, easing = FastOutSlowInEasing),
        label = "title_scale"
    )

    val titleAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, delayMillis = 1200, easing = LinearEasing),
        label = "title_alpha"
    )

    val titleRotationX by animateFloatAsState(
        targetValue = if (startAnimation) 0f else 45f,
        animationSpec = tween(durationMillis = 1500, delayMillis = 1400, easing = FastOutSlowInEasing),
        label = "title_rotation_x"
    )

    // Subtitle animations
    val subtitleOffset by animateDpAsState(
        targetValue = if (startAnimation) 0.dp else 40.dp,
        animationSpec = tween(durationMillis = 1000, delayMillis = 2000, easing = FastOutSlowInEasing),
        label = "subtitle_offset"
    )

    val subtitleAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 2000, easing = LinearEasing),
        label = "subtitle_alpha"
    )

    // Tagline animations
    val taglineAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 2600, easing = LinearEasing),
        label = "tagline_alpha"
    )

    // White background
    val bgColor = Color.White

    // Flash effect when logo zooms - green tint
    val flashAlpha by animateFloatAsState(
        targetValue = if (startAnimation && logoScale < 0.7f) 0.15f else 0f,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "flash_alpha"
    )

    // Initialize particles
    LaunchedEffect(size) {
        if (size != IntSize.Zero && particles.isEmpty()) {
            repeat(150) {
                val angle = Random.nextFloat() * 2 * Math.PI
                particles.add(
                    Particle(
                        pos = Offset(Random.nextFloat() * size.width, Random.nextFloat() * size.height),
                        vel = Offset(cos(angle).toFloat() * 1f, sin(angle).toFloat() * 1f),
                        alpha = Random.nextFloat() * 0.7f + 0.3f,
                        radius = Random.nextFloat() * 3f + 0.5f
                    )
                )
            }
        }
    }

    // Initialize speed lines
    LaunchedEffect(size) {
        if (size != IntSize.Zero && speedLines.isEmpty()) {
            repeat(50) {
                speedLines.add(
                    SpeedLine(
                        pos = Offset(Random.nextFloat() * size.width, Random.nextFloat() * size.height),
                        length = Random.nextFloat() * 150f + 80f,
                        speed = Random.nextFloat() * 12f + 6f,
                        alpha = Random.nextFloat() * 0.6f + 0.3f
                    )
                )
            }
        }
    }

    // Particle animation
    LaunchedEffect(logoScale) {
        while(true) {
            val speedMultiplier = if (logoScale < 0.8f) 4f else 1f
            particles.forEachIndexed { index, particle ->
                var newPos = particle.pos + (particle.vel * speedMultiplier)
                if (newPos.x > size.width) newPos = newPos.copy(x = 0f)
                if (newPos.x < 0) newPos = newPos.copy(x = size.width.toFloat())
                if (newPos.y > size.height) newPos = newPos.copy(y = 0f)
                if (newPos.y < 0) newPos = newPos.copy(y = size.height.toFloat())
                particles[index] = particle.copy(pos = newPos)
            }
            delay(16)
        }
    }

    // Speed lines animation
    LaunchedEffect(logoScale) {
        while(true) {
            val isZooming = logoScale < 0.8f
            speedLines.forEachIndexed { index, line ->
                if (isZooming) {
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val angle = Math.atan2((line.pos.y - centerY).toDouble(), (line.pos.x - centerX).toDouble())
                    val distance = line.speed * 3f
                    val newPos = Offset(
                        line.pos.x + (cos(angle) * distance).toFloat(),
                        line.pos.y + (sin(angle) * distance).toFloat()
                    )

                    if (newPos.x < 0 || newPos.x > size.width || newPos.y < 0 || newPos.y > size.height) {
                        speedLines[index] = line.copy(
                            pos = Offset(centerX + Random.nextFloat() * 100 - 50, centerY + Random.nextFloat() * 100 - 50)
                        )
                    } else {
                        speedLines[index] = line.copy(pos = newPos)
                    }
                } else {
                    var newPos = line.pos.copy(x = line.pos.x - line.speed)
                    if (newPos.x < -line.length) {
                        newPos = Offset(size.width.toFloat() + line.length, Random.nextFloat() * size.height)
                    }
                    speedLines[index] = line.copy(pos = newPos)
                }
            }
            delay(16)
        }
    }

    LaunchedEffect(Unit) {
        delay(300)
        startAnimation = true
        delay(4200)
        onSplashComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        // Flash overlay - green tint
        if (flashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(thintureGreen.copy(alpha = flashAlpha))
            )
        }

        // Particles - green shades
        Canvas(modifier = Modifier.fillMaxSize()) {
            particles.forEach {
                drawCircle(
                    color = thintureGreen,
                    center = it.pos,
                    radius = it.radius,
                    alpha = it.alpha * 0.4f
                )
            }
        }

        // Speed lines - green
        Canvas(modifier = Modifier.fillMaxSize()) {
            speedLines.forEach { line ->
                val lineAlpha = if (logoScale < 0.8f) line.alpha * 1.5f else line.alpha
                drawLine(
                    color = lightGreen.copy(alpha = lineAlpha.coerceAtMost(1f) * logoAlpha * 0.5f),
                    start = line.pos,
                    end = Offset(line.pos.x + line.length, line.pos.y),
                    strokeWidth = 4f
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Spacer(Modifier.weight(0.3f))

            // 3D Zooming Logo with rotating rings
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(280.dp)
            ) {
                // Outer rotating ring - green
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = logoSpinY
                            alpha = logoAlpha
                        }
                ) {
                    drawCircle(
                        color = thintureGreen.copy(alpha = 0.5f),
                        radius = size.width / 2 - 20f,
                        style = Stroke(width = 4f)
                    )

                    for (i in 0 until 8) {
                        val angle = (i * 45f) * (Math.PI / 180)
                        val x = center.x + (size.width / 2 - 20f) * cos(angle).toFloat()
                        val y = center.y + (size.width / 2 - 20f) * sin(angle).toFloat()
                        drawCircle(
                            color = thintureGreen,
                            center = Offset(x, y),
                            radius = 6f
                        )
                    }
                }

                // Middle counter-rotating ring - light green
                Canvas(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            rotationZ = -logoSpinY * 0.7f
                            alpha = logoAlpha * 0.7f
                        }
                ) {
                    drawCircle(
                        color = lightGreen.copy(alpha = 0.4f),
                        radius = size.width / 2 - 15f,
                        style = Stroke(width = 3f)
                    )
                }

                // Main logo with 3D zoom
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Thinture Logo",
                    modifier = Modifier
                        .size(200.dp)
                        .graphicsLayer {
                            scaleX = logoScale * logoPulse
                            scaleY = logoScale * logoPulse
                            rotationX = logoRotationX
                            rotationY = logoRotationY + if (startAnimation) logoSpinY * 0.3f else 0f
                            alpha = logoAlpha
                            cameraDistance = 12 * density
                        }
                        .drawBehind {
                            if (logoAlpha > 0.1f && size.width > 0f && size.height > 0f) {
                                val maxDim = maxOf(size.width, size.height)
                                val glowRadius = maxDim / 1.2f

                                if (glowRadius > 0f) {
                                    val paint = Paint()
                                    val frameworkPaint = paint.asFrameworkPaint()
                                    val transparentColor = Color(0xFF33A638).copy(alpha = 0.0f).toArgb()
                                    val glowIntensity = if (logoScale < 0.8f) 0.6f else 0.3f
                                    val glowColor = Color(0xFF33A638).copy(alpha = glowIntensity * logoAlpha * logoGlow).toArgb()
                                    frameworkPaint.color = transparentColor
                                    frameworkPaint.maskFilter = BlurMaskFilter(maxDim * 0.8f, BlurMaskFilter.Blur.NORMAL)
                                    drawIntoCanvas { canvas ->
                                        canvas.drawCircle(
                                            center = center,
                                            radius = glowRadius,
                                            paint = paint.apply {
                                                shader = RadialGradientShader(
                                                    colors = listOf(Color(glowColor), Color(transparentColor)),
                                                    center = center,
                                                    radius = glowRadius,
                                                    tileMode = TileMode.Clamp
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                )

                // Energy burst effect - green
                if (logoScale < 0.8f) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (i in 0 until 12) {
                            val angle = (i * 30f + logoSpinY) * (Math.PI / 180)
                            val startRadius = size.width / 4
                            val endRadius = size.width / 2
                            drawLine(
                                color = lightGreen.copy(alpha = (0.6f - logoScale) * 1.5f),
                                start = Offset(
                                    center.x + startRadius * cos(angle).toFloat(),
                                    center.y + startRadius * sin(angle).toFloat()
                                ),
                                end = Offset(
                                    center.x + endRadius * cos(angle).toFloat(),
                                    center.y + endRadius * sin(angle).toFloat()
                                ),
                                strokeWidth = 3f
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(50.dp))

            // Title with green gradient
            Text(
                text = "THINTURE",
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(thintureGreen, lightGreen, darkGreen)
                    ),
                    shadow = Shadow(
                        color = thintureGreen.copy(alpha = 0.5f),
                        offset = Offset(0f, 4f),
                        blurRadius = 10f
                    ),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = titleScale
                        scaleY = titleScale
                        rotationX = titleRotationX
                        alpha = titleAlpha
                        cameraDistance = 12 * density
                    }
            )

            Text(
                text = "Technology",
                style = TextStyle(
                    color = thintureGreen,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .offset(y = (-10).dp)
                    .alpha(titleAlpha)
            )

            Spacer(Modifier.height(24.dp))

            // Subtitle - dark gray
            Text(
                text = "Speed Limit Display",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF424242),
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(y = subtitleOffset)
                    .alpha(subtitleAlpha)
            )

            Spacer(Modifier.height(50.dp))

            // Tagline - green
            Text(
                text = "Smart • Accurate • Reliable",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = thintureGreen,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(taglineAlpha)
                    .enhancedShimmer()
            )

            Spacer(Modifier.weight(0.5f))

            // Version - gray
            Text(
                text = "v1.3.0 - MSLD",
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                color = Color(0xFF757575),
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(taglineAlpha)
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

fun Modifier.enhancedShimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    val shimmerOffset by transition.animateFloat(
        initialValue = -200f,
        targetValue = 200f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 2000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    this
        .alpha(shimmerAlpha)
        .drawBehind {
            val shimmerBrush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF33A638).copy(alpha = 0.3f),
                    Color.Transparent
                ),
                start = Offset(shimmerOffset, 0f),
                end = Offset(shimmerOffset + 100f, size.height)
            )
            drawRect(brush = shimmerBrush, blendMode = BlendMode.Plus)
        }
}