package es.altamirano.fmdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import es.altamirano.fmdroid.fm
import es.altamirano.fmdroid.playback.Connection
import es.altamirano.fmdroid.playback.NowState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Por dónde va la canción: una onda hasta donde suena y una línea recta lo que falta. Sólo
 * aparece si se sabe de verdad (Shazam la ha situado o se ha visto empezar) y se sabe lo que
 * dura. En la radio no se puede saltar, así que no se arrastra.
 */
@Composable
fun SongProgress(now: NowState, modifier: Modifier = Modifier) {
    val engine = LocalContext.current.fm.nowPlaying
    var streamNow by remember { mutableDoubleStateOf(engine.streamNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            streamNow = engine.streamNow()
            delay(250)
        }
    }
    val progress = now.progress(streamNow)
    AnimatedVisibility(progress != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        // Al desaparecer, `progress` ya es null: se pinta lo último que hubo.
        val last = remember { arrayOf(0.0 to 1.0) }
        if (progress != null) last[0] = progress
        val (at, length) = last[0]
        Column {
            WavyBar((at / length).toFloat().coerceIn(0f, 1f), moving = now.connection == Connection.PLAYING)
            Row(Modifier.fillMaxWidth()) {
                val style = MaterialTheme.typography.labelSmall.merge(
                    TextStyle(fontFeatureSettings = "tnum", color = MaterialTheme.colorScheme.onSurfaceVariant))
                Text(clock(at), style = style)
                Spacer(Modifier.weight(1f))
                Text(clock(length), style = style)
            }
        }
    }
}

@Composable
private fun WavyBar(fraction: Float, moving: Boolean) {
    val colors = MaterialTheme.colorScheme
    val played = colors.primary
    val rest = colors.onSurface.copy(alpha = 0.18f)
    // La onda corre mientras suena; parada, se queda quieta.
    val wave = remember { Animatable(0f) }
    LaunchedEffect(moving) {
        while (moving) {
            wave.snapTo(wave.value % 1f)
            wave.animateTo(wave.value + 1f, tween(1600, easing = LinearEasing))
        }
    }
    val phase = wave.value
    Canvas(
        Modifier.fillMaxWidth().height(20.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        }
    ) {
        val stroke = 4.dp.toPx()
        val amplitude = 3.dp.toPx()
        val wavelength = 30.dp.toPx()
        val mid = size.height / 2
        val start = stroke / 2
        val end = size.width - stroke / 2
        val x = start + (end - start) * fraction

        // Lo que falta: recto y tenue, desde un poco después del punto.
        val gap = 6.dp.toPx()
        if (x + gap < end) {
            drawLine(rest, Offset(x + gap, mid), Offset(end, mid), stroke, StrokeCap.Round)
        }
        // Lo que ha sonado: la onda, que se aplana al llegar al principio para no empezar en un pico.
        if (x > start) {
            val path = Path()
            val step = 1.dp.toPx()
            var px = start
            path.moveTo(px, mid)
            while (px < x) {
                px = (px + step).coerceAtMost(x)
                val ramp = ((px - start) / wavelength).coerceAtMost(1f)
                val y = mid + amplitude * ramp *
                    sin(2 * PI * ((px - start) / wavelength - phase)).toFloat()
                path.lineTo(px, y)
            }
            drawPath(path, played, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // El punto donde va.
        drawCircle(played, radius = 6.dp.toPx(), center = Offset(x, mid))
    }
}

private fun clock(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
