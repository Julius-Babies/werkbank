package util

import io.opentelemetry.kotlin.attributes.AnyValue
import io.opentelemetry.kotlin.attributes.AttributesMutator
import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanContext
import io.opentelemetry.kotlin.tracing.StatusData
import io.opentelemetry.kotlin.tracing.TraceFlags
import io.opentelemetry.kotlin.tracing.TraceState

object StubSpan : Span {
    override val parent: SpanContext
        get() = object : SpanContext {
            override val traceId: String
                get() = TODO("Not yet implemented")
            override val traceIdBytes: ByteArray
                get() = TODO("Not yet implemented")
            override val spanId: String
                get() = TODO("Not yet implemented")
            override val spanIdBytes: ByteArray
                get() = TODO("Not yet implemented")
            override val traceFlags: TraceFlags
                get() = TODO("Not yet implemented")
            override val isValid: Boolean
                get() = TODO("Not yet implemented")
            override val isRemote: Boolean
                get() = TODO("Not yet implemented")
            override val traceState: TraceState
                get() = TODO("Not yet implemented")
        }

    override val spanContext: SpanContext
        get() = TODO("Not yet implemented")

    override fun isRecording(): Boolean {
        return false
    }

    override fun setName(name: String) {

    }

    override fun setStatus(status: StatusData) {
    }

    override fun end() {
    }

    override fun end(timestamp: Long) {
    }

    override fun setBooleanAttribute(key: String, value: Boolean) {
    }

    override fun setStringAttribute(key: String, value: String) {
    }

    override fun setLongAttribute(key: String, value: Long) {
    }

    override fun setDoubleAttribute(key: String, value: Double) {
    }

    override fun setBooleanListAttribute(key: String, value: List<Boolean>) {
    }

    override fun setStringListAttribute(key: String, value: List<String>) {
    }

    override fun setLongListAttribute(key: String, value: List<Long>) {
    }

    override fun setDoubleListAttribute(key: String, value: List<Double>) {
    }

    override fun setByteArrayAttribute(key: String, value: ByteArray) {
    }

    override fun setAnyValueAttribute(key: String, value: AnyValue) {
    }

    override fun addLink(
        spanContext: SpanContext,
        attributes: (AttributesMutator.() -> Unit)?
    ) {
    }

    override fun addEvent(
        name: String,
        timestamp: Long?,
        attributes: (AttributesMutator.() -> Unit)?
    ) {
    }

}