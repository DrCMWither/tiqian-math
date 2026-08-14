package org.tiqian.math.compose

internal expect fun beginTiqianMathTraceSection(name: String)

internal expect fun endTiqianMathTraceSection()

internal inline fun <T> tiqianMathTraceSection(name: String, block: () -> T): T {
    beginTiqianMathTraceSection(name)
    return try {
        block()
    } finally {
        endTiqianMathTraceSection()
    }
}
