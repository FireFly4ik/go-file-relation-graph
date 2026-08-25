package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.openapi.util.SystemInfo
import java.lang.reflect.Proxy
import javax.swing.JComponent

object MacTrackpadMagnification {
    fun install(component: JComponent, onMagnification: (Double) -> Unit) {
        if (!SystemInfo.isMac) return

        runCatching {
            val gestureListenerClass = Class.forName("com.apple.eawt.event.GestureListener")
            val magnificationListenerClass = Class.forName("com.apple.eawt.event.MagnificationListener")
            val listener = Proxy.newProxyInstance(
                magnificationListenerClass.classLoader,
                arrayOf(magnificationListenerClass),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "magnify" -> {
                        val event = arguments?.firstOrNull() ?: return@newProxyInstance null
                        val value = event.javaClass.getMethod("getMagnification").invoke(event) as Double
                        onMagnification(value)
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "toString" -> "GoFileRelationGraph.MagnificationListener"
                    else -> null
                }
            }
            Class.forName("com.apple.eawt.event.GestureUtilities")
                .getMethod("addGestureListenerTo", JComponent::class.java, gestureListenerClass)
                .invoke(null, component, listener)
            component.putClientProperty(MacTrackpadMagnification::class.java.name, listener)
        }
    }
}
