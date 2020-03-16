import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.DefaultHeaders
import io.ktor.html.respondHtml
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.readText
import io.ktor.http.content.files
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.withContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import java.time.Duration


data class OCRResult(val segments: List<String>)

class Model {
    private val os = mutableListOf<OCRResult>()
    private val notifyCbs = mutableSetOf<suspend (OCRResult) -> Unit>()

    suspend fun pushNew(o: OCRResult) = synchronized(os) {
        os.add(o)
        //copy: to be used outside of sync
        notifyCbs.toList()
    }.let { for (cb in it) safely { cb(o) } }

    fun subscribe(cb: suspend (OCRResult) -> Unit): () -> Unit = synchronized(os) {
        notifyCbs += cb
        { synchronized(os) { notifyCbs -= cb } }
    }

    //gets a copy: so safe to pass out (no longer synchronized)
    // - immmutable coll would probably be better... but whatever for now
    val ocrList: List<OCRResult> get() = synchronized(os) { os.reversed() }
}

val portServe = (System.getenv("PORT") ?: "7891").toInt()

fun listen(m: Model) {
//    val rs = listOf(
//        listOf("hello", "there"),
//        listOf("foo", "there", "zen"),
//        listOf("test", "there"),
//        listOf("hello", "there")
//    )

    val server = embeddedServer(Netty, port = portServe) {
        install(DefaultHeaders)
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
            //timeout = Duration.ofSeconds(15)
            maxFrameSize =
                Long.MAX_VALUE // Disabled (max value). The connection will be closed if surpassed this length.
            masking = false
        }
        routing {
            static("static") {
                files("static")

                resources("static")
            }

            get("/") {
                //call.respondText("Hello World!", ContentType.Text.Plain)
                call.respondRedirect("/ocrs")
            }

            get("/ocrs") {
                call.respondHtml {
                    head {
                        styleLink("/static/style.css")
                        script(src = "/static/fetch.js") {}
                    }
                    body {
                        for (r in m.ocrList)
                            consumer.htmlOcrSegment(r.segments)
                    }
                }
            }

            webSocket("/news") {
                val ctx = coroutineContext
                val stop = m.subscribe { or ->
                    val str = buildString {
                        appendHTML().htmlOcrSegment(or.segments)
                    }
                    withContext(ctx) {
                        send(Frame.Text(str))
                    }
                }
                try {
                    for (frame in incoming) {
                        //ignore - just 'wait' until socket closed
                        when (frame) {
                            is Frame.Text -> {
                                println(frame.readText())
                            }
                        }
                    }

                } finally {
                    stop()
                }
            }
        }
    }
    server.start(wait = true)
}


fun TagConsumer<*>.htmlOcrSegment(txt: List<String>) {
    div("segment") {
        for (t in txt) {
            span("jline") {
                +t
            }
            //+" "
        }
    }
}