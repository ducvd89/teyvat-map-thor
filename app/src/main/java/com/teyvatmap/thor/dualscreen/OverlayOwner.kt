package com.teyvatmap.thor.dualscreen

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The view-tree owners a Compose UI needs when it is hosted by a service-owned window instead of an Activity:
 * lifecycle, saved state, view models and the back-press dispatcher. Main thread only.
 */
class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val dispatcher = OnBackPressedDispatcher(null)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = dispatcher

    fun create() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    fun onBackPressed() = dispatcher.onBackPressed()
}
