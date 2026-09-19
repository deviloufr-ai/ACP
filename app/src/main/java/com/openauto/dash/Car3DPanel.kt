package com.openauto.dash

import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder

private var filamentInitialized = false

/**
 * Renders the car's 3D model (`assets/car.glb`) with Filament — rotate/zoom with
 * touch. Logs the model's node names so we can later target moving parts (doors,
 * hood) once the GLB exports them with names. No IBL asset is bundled, so a key
 * sun + fill light provide the shading.
 */
@Composable
fun Car3DPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (!filamentInitialized) {
        Utils.init()
        filamentInitialized = true
    }

    val surfaceView = remember { SurfaceView(context) }
    val choreographer = remember { Choreographer.getInstance() }

    val modelViewer = remember {
        ModelViewer(surfaceView).also { mv ->
            surfaceView.setOnTouchListener(mv)

            // Lighting (no IBL bundled): a bright key sun + a softer fill.
            val engine = mv.engine
            val em = EntityManager.get()
            val key = em.create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1f, 1f, 1f)
                .intensity(100_000f)
                .direction(0.3f, -0.7f, -0.6f)
                .castShadows(true)
                .build(engine, key)
            mv.scene.addEntity(key)
            val fill = em.create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.9f, 0.95f, 1f)
                .intensity(35_000f)
                .direction(-0.4f, -0.3f, 0.6f)
                .castShadows(false)
                .build(engine, fill)
            mv.scene.addEntity(fill)

            runCatching {
                context.assets.open("car.glb").use { input ->
                    val bytes = input.readBytes()
                    val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    bb.put(bytes)
                    bb.rewind()
                    mv.loadModelGlb(bb)
                }
                mv.transformToUnitCube()
                // Log node names to discover animatable parts (doors, hood…).
                mv.asset?.let { asset ->
                    asset.entities.forEach { e ->
                        Log.d("Car3D", "node: ${asset.getName(e)}")
                    }
                }
            }.onFailure { Log.e("Car3D", "Failed to load car.glb", it) }
        }
    }

    val frameCallback = remember {
        object : Choreographer.FrameCallback {
            private var startNanos = 0L
            override fun doFrame(frameTimeNanos: Long) {
                choreographer.postFrameCallback(this)
                if (startNanos == 0L) startNanos = frameTimeNanos
                // Drive any glTF animations baked into car.glb (e.g. "WheelSpin"),
                // looping clip 0 on wall-clock time. Filament plays nothing on its
                // own — without this the model is static.
                modelViewer.animator?.let { animator ->
                    if (animator.animationCount > 0) {
                        val elapsed = (frameTimeNanos - startNanos) / 1_000_000_000.0
                        val dur = animator.getAnimationDuration(0)
                        val t = if (dur > 0f) (elapsed % dur).toFloat() else elapsed.toFloat()
                        animator.applyAnimation(0, t)
                        animator.updateBoneMatrices()
                    }
                }
                modelViewer.render(frameTimeNanos)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        choreographer.postFrameCallback(frameCallback)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> choreographer.postFrameCallback(frameCallback)
                Lifecycle.Event.ON_PAUSE -> choreographer.removeFrameCallback(frameCallback)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    AndroidView(factory = { surfaceView }, modifier = modifier.fillMaxSize())
}
