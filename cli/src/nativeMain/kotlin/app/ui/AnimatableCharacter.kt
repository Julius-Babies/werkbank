package app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Composable
fun AnimatableCharacter(
    characters: List<String>,
    index: Int,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
) {
    Text(
        value = characters[index % characters.size],
        color = color,
        background = background,
    )
}

@Composable
fun AnimatableCharacter(
    characters: List<String>,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
) {
    var animationIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        launch {
            while (isActive) {
                delay(1.seconds)
                animationIndex++
            }
        }
    }
    AnimatableCharacter(
        characters = characters,
        index = animationIndex,
        color = color,
        background = background,
    )
}