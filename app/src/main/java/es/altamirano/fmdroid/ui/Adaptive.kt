package es.altamirano.fmdroid.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Modo escritorio (ventanas que se redimensionan), tableta o móvil tumbado.

/** Ancho al que el contenido deja de estirarse. */
val READABLE_WIDTH = 720.dp

/**
 * El contenido de una lista centrado y sin pasar de [max], con el margen medido en el sitio: en
 * la tableta la lista comparte la ventana con el reproductor. El margen va como `contentPadding`
 * para que la rueda del ratón desplace también sobre él.
 */
@Composable
fun Readable(modifier: Modifier = Modifier, max: Dp = READABLE_WIDTH, content: @Composable (side: Dp) -> Unit) {
    BoxWithConstraints(modifier) { content(maxOf(0.dp, (maxWidth - max) / 2)) }
}

/** El clic derecho del ratón hace lo que la pulsación larga. */
fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    current.value()
                }
            }
        }
    }
}
