package com.aitddemo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The spinning Infogrames "Tatou" (armadillo) 3D logo of the boot sequence: its body is read from ITD_RESS.PAK
 * ([StartOriginalReader]) and drawn with the shared rasterizer in ModelRenderer.kt every frame.
 */
@Composable
fun TatouSpinner(modifier: Modifier = Modifier) {
    val o = StartAssets.original ?: return
    ModelSpinner(
        vertices = o.tatouVertices,
        triangles = o.tatouTriangles,
        rotationSpeed = 1.4f, // radians/sec
        modifier = modifier.fillMaxSize(),
    )
}
