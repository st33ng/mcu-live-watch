package dev.st33ng.mculivewatch.ui

/**
 * A value read from the target.
 *
 * Each variant declares `toString()` explicitly: a Kotlin `data class` generates its own
 * `toString()` even when the base class overrides it, so the inherited implementation was never
 * used and the Value column displayed `IntValue(value=489583)` instead of `489583`.
 * `displayText()` exists as well so callers do not have to rely on `toString()` at all.
 */
sealed class CppValue {
    data class IntValue(val value: Int) : CppValue() {
        override fun toString(): String = value.toString()
    }

    data class FloatValue(val value: Float) : CppValue() {
        override fun toString(): String = value.toString()
    }

    data class DoubleValue(val value: Double) : CppValue() {
        override fun toString(): String = value.toString()
    }

    data class BoolValue(val value: Boolean) : CppValue() {
        override fun toString(): String = value.toString()
    }

    data class StringValue(val value: String) : CppValue() {
        override fun toString(): String = value
    }

    /** Locale-independent text for the table cell. */
    fun displayText(): String = when (this) {
        is IntValue -> value.toString()
        is FloatValue -> value.toString()
        is DoubleValue -> value.toString()
        is BoolValue -> value.toString()
        is StringValue -> value
    }

    fun toPlotValue(): Double? = when (this) {
        is IntValue -> value.toDouble()
        is FloatValue -> value.toDouble()
        is DoubleValue -> value
        is BoolValue -> if (value) 1.0 else 0.0
        else -> null
    }

    companion object {
        fun parse(raw: String): CppValue = when {
            raw.equals("true", ignoreCase = true) -> BoolValue(true)
            raw.equals("false", ignoreCase = true) -> BoolValue(false)
            raw.toIntOrNull() != null -> IntValue(raw.toInt())
            raw.toDoubleOrNull() != null -> DoubleValue(raw.toDouble())
            else -> StringValue(raw)
        }
    }
}
