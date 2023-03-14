package dev.evo.prometheus.ktor

import dev.evo.prometheus.*
import dev.evo.prometheus.hiccup.HiccupMetrics
import dev.evo.prometheus.util.measureTimeMillis
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*

expect val processMetrics: ProcessMetrics

fun Application.metricsModule(
    metrics: PrometheusMetrics = Metrics(),
    enablePathLabel: Boolean = false,
    startHiccups: Boolean = false
) {
    install(MetricsPlugin) {
        this.metrics = metrics
        this.startHiccups = startHiccups
        this.enablePathLabel = enablePathLabel
    }
    if (metrics is Metrics && startHiccups) {
        metrics.hiccups.startTracking(this@metricsModule)
    }
    routing {
        metrics(metrics)
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

val MetricsPlugin = createApplicationPlugin(name = "MetricsPlugin", ::MetricsConfig) {
    val metrics = pluginConfig.metrics
    val routeKey = AttributeKey<Route>("Route info")
    val enablePathLabel = pluginConfig.enablePathLabel
    if (metrics is HttpMetrics) {
        with(application) {
            environment.monitor.subscribe(Routing.RoutingCallStarted) { call ->
                call.attributes.put(routeKey, call.route)
            }
            intercept(ApplicationCallPipeline.Monitoring) {
                val requestTimeMs = measureTimeMillis {
                    metrics.inFlightRequests?.incAndDec({
                        fromCall(call, routeKey, enablePathLabel)
                    }) {
                        proceed()
                    } ?: proceed()
                }

                val requestSize = call.request.receiveChannel().availableForRead.toDouble()
                metrics.requestSizes?.observe(requestSize) {
                    fromCall(call, routeKey, enablePathLabel)
                }
                metrics.totalRequests?.observe(requestTimeMs) {
                    fromCall(call, routeKey, enablePathLabel)
                }
            }
            sendPipeline.intercept(ApplicationSendPipeline.After) {
                val response = subject as OutgoingContent
                val responseSize = response.contentLength?.toDouble()
                responseSize?.let {
                    metrics.responseSizes?.observe(responseSize) {
                        fromCall(call, routeKey, enablePathLabel)
                    }
                }
            }
        }
    }
}

@KtorDsl
class MetricsConfig {
    var metrics: PrometheusMetrics = Metrics()
    var enablePathLabel = false
    var startHiccups = false
}

internal fun HttpRequestLabels.fromCall(
    call: ApplicationCall,
    routeKey: AttributeKey<Route>,
    enablePathLabel: Boolean
) {
    method = call.request.httpMethod
    statusCode = call.response.status()
    route = call.attributes.getOrNull(routeKey)
    if (enablePathLabel) {
        path = call.request.path()
    }
}

class Metrics(customMetrics: PrometheusMetrics = EmptyMetrics, config: HttpMetrics.Config = HttpMetrics.Config()) :
    PrometheusMetrics(),
    HttpMetrics {
    val http by submetrics(StandardHttpMetrics(config))
    val hiccups by submetrics(HiccupMetrics())
    val process by submetrics(processMetrics)
    val customMetrics by submetrics(customMetrics)

    override val totalRequests: Histogram<HttpRequestLabels>
        get() = http.totalRequests
    override val inFlightRequests: GaugeLong<HttpRequestLabels>
        get() = http.inFlightRequests
    override val requestSizes: Histogram<HttpRequestLabels>
        get() = http.requestSizes
    override val responseSizes: Histogram<HttpRequestLabels>
        get() = http.responseSizes
}

private object EmptyMetrics : PrometheusMetrics()

interface HttpMetrics {
    val totalRequests: Histogram<HttpRequestLabels>?
        get() = null
    val inFlightRequests: GaugeLong<HttpRequestLabels>?
        get() = null
    val requestSizes: Histogram<HttpRequestLabels>?
        get() = null
    val responseSizes: Histogram<HttpRequestLabels>?
        get() = null

    data class Config(
        val prefix: String = "http",
        val totalRequestsRange: IntRange = IntRange(0, 4),
        val requestSizesRange: IntRange = IntRange(0, 6),
        val responseSizesRange: IntRange = IntRange(0, 6)
    )
}

class StandardHttpMetrics(config: HttpMetrics.Config) : PrometheusMetrics() {
    private val prefix = config.prefix

    val totalRequests by histogram(
        "${prefix}_total_requests", logScale(config.totalRequestsRange)
    ) { HttpRequestLabels() }
    val inFlightRequests by gaugeLong("${prefix}_in_flight_requests") {
        HttpRequestLabels()
    }
    val requestSizes by histogram(
        "${prefix}_request_size_bytes", logScale(config.requestSizesRange)
    ) { HttpRequestLabels() }
    val responseSizes by histogram(
        "${prefix}_response_size_bytes", logScale(config.responseSizesRange)
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