package org.tiqian.math.compose

import android.os.Trace

internal actual fun beginTiqianMathTraceSection(name: String) {
    Trace.beginSection(name)
}

internal actual fun endTiqianMathTraceSection() {
    Trace.endSection()
}
