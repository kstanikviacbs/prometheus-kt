package dev.evo.prometheus.ktor

import dev.evo.prometheus.*
import dev.evo.prometheus.hiccup.HiccupMetrics
import dev.evo.prometheus.util.measureTimeMillis
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.Routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*

expect val processMetrics: ProcessMetrics

fun Application.metricsModule(startHiccups: Boolean = true) {
    val feature = MetricsFeature()
    if (startHiccups) {
        feature.metrics.hiccups.startTracking(this@metricsModule)
    }

    metricsModule(feature)
}

fun Application.metricsModule(metrics: PrometheusMetrics) {
    metricsModule(MetricsFeature(metrics))
}

fun <TMetrics : HttpMetrics> Application.metricsModule(
    metricsFeature: MetricsFeature<TMetrics>
) {
    install(metricsFeature)

    routing {
        metrics(metricsFeature.metrics.metrics)
    }
}

fun Route.metrics(metrics: PrometheusMetrics) {
    get("/metrics") {
        metrics.collect()
        call.respondText {
            with(StringBuilder()) {
                writeSamples(metrics.dump(), this)
                this.toString()
            }
        }
    }
}

open class MetricsFeature<TMetrics : HttpMetrics>(val metrics: TMetrics) :
    BaseApplicationPlugin<Application, MetricsFeature.Configuration, Unit> {
    override val key = AttributeKey<Unit>("Response metrics collector")
    private val routeKey = AttributeKey<Route>("Route info")

    companion object {
        operator fun invoke(): MetricsFeature<DefaultMetrics> {
            return MetricsFeature(DefaultMetrics())
        }

        operator fun invoke(prometheusMetrics: PrometheusMetrics): MetricsFeature<DummyMetrics> {
            return MetricsFeature(DummyMetrics(prometheusMetrics))
        }
    }

    class Configuration {
        var totalRequests: Histogram<HttpRequestLabels>? = null
        var inFlightRequests: GaugeLong<HttpRequestLabels>? = null
        var requestSizes: Histogram<HttpRequestLabels>? = null
        var responseSizes: Histogram<HttpRequestLabels>? = null
        var enablePathLabel = false
    }

    open fun defaultConfiguration(): Configuration {
        return Configuration().apply {
            totalRequests = metrics.totalRequests
            inFlightRequests = metrics.inFlightRequests
            requestSizes = metrics.requestSizes
            responseSizes = metrics.responseSizes
        }
    }


    override fun install(pipeline: Application, configure: Configuration.() -> Unit) {
        val configuration = defaultConfiguration().apply(configure)
        pipeline.environment.monitor.subscribe(Routing.RoutingCallStarted) { call ->
            call.attributes.put(routeKey, call.route)
        }

        pipeline.intercept(ApplicationCallPipeline.Monitoring) {
            val requestTimeMs = measureTimeMillis {
                configuration.inFlightRequests?.incAndDec({
                    fromCall(call, configuration.enablePathLabel)
                }) {
                    proceed()
                } ?: proceed()
            }

            val requestSize = call.request.receiveChannel().availableForRead.toDouble()
            configuration.requestSizes?.observe(requestSize) {
                fromCall(call, configuration.enablePathLabel)
            }
            configuration.totalRequests?.observe(requestTimeMs) {
                fromCall(call, configuration.enablePathLabel)
            }
        }
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.After) {
            val response = subject as OutgoingContent
            val responseSize = response.contentLength?.toDouble()
            responseSize?.let {
                configuration.responseSizes?.observe(responseSize) {
                    fromCall(call, configuration.enablePathLabel)
                }
            }
        }
    }

    private fun HttpRequestLabels.fromCall(call: ApplicationCall, enablePathLabel: Boolean) {
        method = call.request.httpMethod
        statusCode = call.response.status()
        route = call.attributes.getOrNull(routeKey)
        if (enablePathLabel) {
            path = call.request.path()
        }
    }
}

interface HttpMetrics {
    val totalRequests: Histogram<HttpRequestLabels>?
        get() = null
    val inFlightRequests: GaugeLong<HttpRequestLabels>?
        get() = null

    val requestSizes: Histogram<HttpRequestLabels>?
        get() = null

    val responseSizes: Histogram<HttpRequestLabels>?
        get() = null

    val metrics: PrometheusMetrics
}

class DefaultMetrics : PrometheusMetrics(), HttpMetrics {
    val process by submetrics(processMetrics)
    val hiccups by submetrics(HiccupMetrics())
    val http by submetrics(StandardHttpMetrics())

    override val totalRequests: Histogram<HttpRequestLabels>?
        get() = http.totalRequests
    override val inFlightRequests: GaugeLong<HttpRequestLabels>?
        get() = http.inFlightRequests
    override val requestSizes: Histogram<HttpRequestLabels>?
        get() = http.requestSizes

    override val responseSizes: Histogram<HttpRequestLabels>?
        get() = http.responseSizes

    override val metrics: PrometheusMetrics
        get() = this
}

class DummyMetrics(private val prometheusMetrics: PrometheusMetrics) : HttpMetrics {
    override val metrics: PrometheusMetrics
        get() = prometheusMetrics
}

class StandardHttpMetrics : PrometheusMetrics() {
    private val prefix = "http"

    val totalRequests by histogram(
        "${prefix}_total_requests", logScale(0, 3)
    ) { HttpRequestLabels() }
    val inFlightRequests by gaugeLong("${prefix}_in_flight_requests") {
        HttpRequestLabels()
    }
    val requestSizes by histogram(
        "${prefix}_request_size_bytes", logScale(0, 3)
    ) { HttpRequestLabels() }
    val responseSizes by histogram(
        "${prefix}_response_size_bytes", logScale(0, 3)
    ) { HttpRequestLabels() }
}

class HttpRequestLabels : LabelSet() {
    var method: HttpMethod? by label { value }
    var statusCode: HttpStatusCode? by label("response_code") { value.toString() }
    var route: Route? by label {
        toLabelString()
    }
    var path by label()

    fun Route.toLabelString(): String {
        val segment = when (selector) {
            is PathSegmentConstantRouteSelector -> selector
            is PathSegmentParameterRouteSelector -> selector
            is PathSegmentOptionalParameterRouteSelector -> selector
            is PathSegmentTailcardRouteSelector -> selector
            is PathSegmentWildcardRouteSelector -> selector
            else -> null
        }

        val parent = parent
        return when {
            segment == null -> parent?.toLabelString() ?: "/"
            parent == null -> "/$segment"
            else -> {
                val parentSegment = parent.toLabelString()
                when {
                    parentSegment.isEmpty() -> segment.toString()
                    parentSegment.endsWith('/') -> "$parentSegment$segment"
                    else -> "$parentSegment/$segment"
                }
            }
        }
    }
}