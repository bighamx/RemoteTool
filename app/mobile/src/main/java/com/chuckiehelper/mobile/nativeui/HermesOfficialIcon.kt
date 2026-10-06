package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.chuckiehelper.mobile.R

/** Draw the official SVG mark only, with no plate or background; paths retain their winding. */
@Composable
fun HermesOfficialIcon(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val path = remember(resources) {
        val data = resources.openRawResource(R.raw.hermes_official_mark).bufferedReader(Charsets.UTF_8).use { it.readText() }
        PathParser().parsePathString(data).toPath().apply {
            asAndroidPath().transform(android.graphics.Matrix().apply {
                setValues(floatArrayOf(0.16893308f, 0f, 50.12227f, 0f, 0.16893308f, -328.54902f, 0f, 0f, 1f))
            })
        }
    }
    val color = LocalContentColor.current
    Canvas(modifier.semantics { contentDescription = "Hermes" }) {
        withTransform({ scale(size.width / 1024f, size.height / 1024f, Offset.Zero) }) {
            clipRect(0f, 0f, 1024f, 1024f) { drawPath(path, color) }
        }
    }
}
