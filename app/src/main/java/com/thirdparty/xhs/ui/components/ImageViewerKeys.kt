package com.thirdparty.xhs.ui.components

/**
 * Lets the full-screen image viewer take the volume keys for paging.
 *
 * Registered only while the viewer is on screen and cleared when it leaves, so
 * the keys behave normally everywhere else — that "only in full screen" part is
 * the whole point, and a plain global handler would leak out of the viewer.
 *
 * The viewer itself is a composable, but volume keys are delivered to the
 * Activity first, so MainActivity checks this registry in onKeyDown.
 */
object ImageViewerKeys {

    /** step is -1 for the previous image, +1 for the next. */
    @Volatile
    private var handler: ((Int) -> Unit)? = null

    fun register(onStep: (Int) -> Unit) {
        handler = onStep
    }

    fun unregister() {
        handler = null
    }

    /** True when a viewer consumed the key, so the caller must not pass it on. */
    fun handle(step: Int): Boolean {
        val h = handler ?: return false
        h(step)
        return true
    }
}
