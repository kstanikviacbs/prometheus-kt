![Build Status](https://github.com/anti-social/prometheus-kt/workflows/Java%20CI/badge.svg)
[![codecov](https://codecov.io/gh/anti-social/prometheus-kt/branch/master/graph/badge.svg)](https://codecov.io/gh/anti-social/prometheus-kt)
[![Download](https://maven-badges.herokuapp.com/maven-central/dev.evo.prometheus/prometheus-kt/badge.svg)](https://maven-badges.herokuapp.com/maven-central/dev.evo.prometheus/prometheus-kt)

# Prometheus-kt
Prometheus client for Kotlin

## Motivation

1. Kotlin multiplatform support
2. Coroutines friendly (does not use `synchronized` at all)
3. Typed labels
4. Nice DSL
5. Ktor support out of the box

At the moment official prometheus java client has a bit more performance.

## Versions

| Version | Kotlin version |
|---------|----------------|
| 0.1.1   | 1.4.30         |
| 0.1.2   | 1.5.21         |
| 0.1.3   | 1.5.31         |

See [Kotlin release details](https://kotlinlang.org/docs/releases.html#release-details)
for kotlinx library versions compatibility.

## How to use?

Add it into your build script:

`build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.evo.prometheus", "prometheus-kt-ktor", "0.1.2")
}
```

`build.gradle`:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "dev.evo.prometheus:prometheus-kt-ktor:0.1.2"
}
```

Create your own metrics:

```kotlin
import dev.evo.prometheus.LabelSet
import dev.evo.prometheus.PrometheusMetrics
import dev.evo.prometheus.jvm.DefaultJvmMetrics

class ProcessingLabels : LabelSet() {
    var source by label()
}

object AppMetrics : PrometheusMetrics() {
    val processedProducts by histogram("processed_products", logScale(0, 2)) {
        ProcessingLabels()
    }
    val jvm by submetrics(DefaultJvmMetrics())
}
```

Use them in your application:

```kotlin
import kotlinx.coroutines.delay

suspend fun startProcessing() {
    while (true) {
        AppMetrics.processedProducts.measureTime({
            source = "manual"
        }) {
            // Processing
            delay(100)
        }
    }
}
```

Then expose them:

```kotlin
import dev.evo.prometheus.ktor.metricsModule

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

suspend fun main(args: Array<String>) {
    val metricsApp = embeddedServer(
        Netty,
        port = 9090,
        module = {
            metricsModule(AppMetrics)
        }
    )
            .start(wait = false)
    
    // Start processing
    startProcessing()
    
    metricsApp.stop(1000, 2000)
}
```

And finally watch them:

```bash
curl -X GET 'localhost:9090/metrics'
```

More samples at: https://github.com/anti-social/prometheus-kt/tree/master/samples

Just run them:

```bash
./gradlew --project-dir samples/processing-app run
```

```bash
./gradlew --project-dir samples/http-app run
```
