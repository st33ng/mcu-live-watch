package com.jfrog.MyPlugin.clion.toolWindow

sealed class CppValue {
    data class IntValue(val value: Int) : CppValue()
    data class FloatValue(val value: Float) : CppValue()
    data class DoubleValue(val value: Double) : CppValue()
    data class BoolValue(val value: Boolean) : CppValue()
    data class StringValue(val value: String) : CppValue()

    override fun toString(): String = when (this) {
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
        fun parse(raw: String): CppValue {
            val value = when {
                raw.equals("true", ignoreCase = true) -> BoolValue(true)
                raw.equals("false", ignoreCase = true) -> BoolValue(false)
                raw.toIntOrNull() != null -> IntValue(raw.toInt())
                raw.toDoubleOrNull() != null -> DoubleValue(raw.toDouble())
                else -> StringValue(raw)
            }
            //println("Parsed value: $value (type: ${value::class.simpleName})")
            return value
        }
    }
}
