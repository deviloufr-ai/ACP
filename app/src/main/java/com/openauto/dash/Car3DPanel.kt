package com.openauto.dash

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels

private var filamentInitialized = false

/**
 * Renders the car's 3D model (`assets/car.glb`) with Filament — rotate/zoom with
 * touch. No IBL asset is bundled, so a key sun + fill light provide the shading.
 *
 * The head unit's GPU is weak, so frames are drawn only when they can change:
 * at full rate while a finger is on the model (and for a moment after), at
 * 30 fps while the model's baked animation (the spinning wheels) plays, and
 * not at all otherwise.
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
    val scene = remember { CarScene(surfaceView) }

    // The 21 MB model is read off the main thread; Filament takes it on the
    // main thread, which owns the engine.
    LaunchedEffect(scene) {
        val glb = try {
            withContext(Dispatchers.IO) { readAsset(context, "car.glb") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Car3D", "Failed to load car.glb", e)
            return@LaunchedEffect
        }
        scene.load(glb)
    }

    DisposableEffect(lifecycleOwner, scene) {
        // Replayed up to the current state when added: a resumed dashboard
        // starts the loop right away, and only this one chain of frames runs.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scene.loop.start()
                Lifecycle.Event.ON_PAUSE -> scene.loop.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scene.destroy()
        }
    }

    AndroidView(factory = { surfaceView }, modifier = modifier.fillMaxSize())
}

/** The model viewer, its two extra lights and its render loop, created and torn down together. */
private class CarScene(private val surfaceView: SurfaceView) {
    val viewer = ModelViewer(surfaceView)
    val loop = CarRenderLoop(viewer)
    private val lights: IntArray

    init {
        val engine = viewer.engine
        // Shadows cost a whole extra pass per frame and add little to a model
        // turning in the dark: off for every light, the viewer's own included.
        viewer.view.setShadowingEnabled(false)

        // Lighting (no IBL bundled): a bright key sun + a softer fill.
        val em = EntityManager.get()
        val key = em.create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 1f, 1f)
            .intensity(100_000f)
            .direction(0.3f, -0.7f, -0.6f)
            .castShadows(false)
            .build(engine, key)
        viewer.scene.addEntity(key)
        val fill = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.9f, 0.95f, 1f)
            .intensity(35_000f)
            .direction(-0.4f, -0.3f, 0.6f)
            .castShadows(false)
            .build(engine, fill)
        viewer.scene.addEntity(fill)
        lights = intArrayOf(key, fill)

        surfaceView.setOnTouchListener { v, event ->
            loop.wake()
            viewer.onTouch(v, event)
        }
        // A new size needs a new frame, even with nothing moving.
        surfaceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loop.wake() }
    }

    fun load(glb: ByteBuffer) {
        if (loop.disposed || !viewer.engine.isValid) return
        runCatching {
            viewer.loadModelGlb(glb)
            viewer.transformToUnitCube()
        }.onFailure { Log.e("Car3D", "Failed to load car.glb", it) }
        loop.wake()
    }

    /**
     * Lets everything go. ModelViewer tears itself down (model, loaders,
     * renderer, engine) when its view leaves the window, which happens around
     * now as the AndroidView goes; our own lights are ours to free. A view
     * that never made it onto the window would never get that call, so the
     * viewer is destroyed here instead.
     */
    fun destroy() {
        loop.dispose()
        val engine = viewer.engine
        if (engine.isValid) {
            for (e in lights) {
                viewer.scene.removeEntity(e)
                engine.destroyEntity(e)
            }
        }
        val em = EntityManager.get()
        for (e in lights) em.destroy(e)
        if (engine.isValid && !surfaceView.isAttachedToWindow) viewer.destroy()
    }
}

/**
 * One chain of frame callbacks at most, stopped while the dashboard is paused
 * and whenever there is nothing new to draw.
 */
private class CarRenderLoop(private val viewer: ModelViewer) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var resumed = false
    private var posted = false
    var disposed = false
        private set

    /** Frames at the full rate until then (a touch, a resize...), System.nanoTime() based like frame times. */
    private var busyUntilNanos = 0L
    /** A frame is owed (asked for and not drawn yet, e.g. the surface was not ready). */
    private var owed = true
    private var startNanos = 0L
    private var lastRenderNanos = 0L

    fun start() {
        resumed = true
        wake()
    }

    fun stop() {
        resumed = false
        unpost()
    }

    fun dispose() {
        disposed = true
        unpost()
    }

    /** Something may have changed on screen: draw at the full rate for a moment. */
    fun wake() {
        busyUntilNanos = maxOf(busyUntilNanos, System.nanoTime() + BUSY_LINGER_NS)
        owed = true
        post()
    }

    private fun post() {
        if (!resumed || disposed || posted) return
        posted = true
        choreographer.postFrameCallback(this)
    }

    private fun unpost() {
        if (!posted) return
        posted = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        // The engine goes with the view (see CarScene.destroy), possibly within
        // the very frame this callback was already queued for.
        if (disposed || !resumed || !viewer.engine.isValid) return
        if (viewer.asset == null) return // nothing to draw until the model is in; load() wakes the loop
        val loading = viewer.progress < 1f
        val busy = frameTimeNanos < busyUntilNanos || loading
        val animated = (viewer.animator?.animationCount ?: 0) > 0
        if (!busy && !animated && !owed) return // idle: the next touch wakes the loop
        if (busy || owed || frameTimeNanos - lastRenderNanos >= ANIMATION_FRAME_NS) {
            if (startNanos == 0L) startNanos = frameTimeNanos
            // Drive any glTF animations baked into car.glb (e.g. "WheelSpin"),
            // looping clip 0 on wall-clock time. Filament plays nothing on its
            // own — without this the model is static.
            viewer.animator?.let { animator ->
                if (animator.animationCount > 0) {
                    val elapsed = (frameTimeNanos - startNanos) / 1_000_000_000.0
                    val dur = animator.getAnimationDuration(0)
                    val t = if (dur > 0f) (elapsed % dur).toFloat() else elapsed.toFloat()
                    animator.applyAnimation(0, t)
                    animator.updateBoneMatrices()
                }
            }
            if (viewer.render(frameTimeNanos)) {
                lastRenderNanos = frameTimeNanos
                owed = false
            }
        }
        post()
    }

    private companion object {
        /** How long after the last touch the model keeps turning smoothly (the camera eases out). */
        const val BUSY_LINGER_NS = 1_500_000_000L
        const val ANIMATION_FPS = 30
        /** A little under 1/30 s, so a 60 Hz vsync's jitter still lands every other frame. */
        const val ANIMATION_FRAME_NS = 1_000_000_000L / ANIMATION_FPS - 4_000_000L
    }
}

/** Reads a whole asset straight into one direct buffer, sized up front (no second copy). */
private fun readAsset(context: Context, name: String): ByteBuffer =
    context.assets.open(name).use { input ->
        // An asset stream knows its remaining length, compressed or not.
        val size = input.available()
        if (size <= 0) {
            val bytes = input.readBytes()
            return@use ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        }
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        val channel = Channels.newChannel(input)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        check(!buffer.hasRemaining()) { "$name ended early" }
        buffer.rewind()
        buffer
    }
