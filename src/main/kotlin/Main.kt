import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.selects.select
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.lang.Exception
import java.nio.file.*
import java.rmi.UnexpectedException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val jmapper = jacksonObjectMapper()

val pathWatch = System.getenv("WATCH_PATH") ?: "pngs"

val errs = Channel<Exception>(10)
inline fun <T> safely(block: () -> T) {
    try {
        block()
    } catch (e: Exception) {
        errs.sendBlocking(e)
    }
}

fun main() {
    GlobalScope.launch{
        for (e in errs) safely {
            System.err.println(e.printStackTrace())
            delay(100) //only process 10 errors/s: prevent some loop from exploding by quickly throwing over and over
        }
    }

    //testOcrSpace()

    watchListen()
}

fun watchListen() {
    val fileEvents = Channel<Path>(20) //events as they come
    val os = Channel<OCRResult>()
    val m = Model()

    val watcher = watchFolderLaunch(fileEvents)

    GlobalScope.launch {
        safely {

            launch {
                for (o in os) safely { m.pushNew(o) }
            }

            launch {
                watchOCR(fileEvents, os)
            }

            //for DEBUG:
            @Suppress("UNREACHABLE_CODE")
            if (false)
                launch {
                    //return@launch

                    suspend fun push(vararg s: String) {
                        os.send(OCRResult(listOf(*s)))
                        delay(4000)
                    }

                    while (true) {
                        delay(10000)
                        push("新あたらしい記事きじを書かこうという", "気持きもちになるまで長ながい時間じかんがかかった。", "書かきたいことはたくさんあったけれど、")
                        push("息子むすこを産うんだ後あとは書かく時間じかんがあまりなかった。", "幸運こううんにも、息子むすこはこの四月しがつから")
                        push("保育園ほいくえんに入はいることができ", "私わたしはまた働はたらき始はじめた")
                        push("日本にほんでは、近頃ちかごろ多おおくの人ひとが保育園ほいくえん問題もんだいについて話はなしている。")
                        push("特とくに東京とうきょうでは", "十分じゅうぶんな施設しせつがないので、", "子こどもを保育園ほいくえんに入いれることがとても大変たいへんだ。")
                    }
                }
        }
    }

    listen(m)
}

fun testOcrSpace() {
    val imgPath =
        "C:\\Users\\chmel\\Desktop\\screenshot_tmp\\2020-03-09 00_01_06-ＮＡＫＥＤ☆ＰＬＵＳver1.0 　　　　 F4_画面変更 _ F8_情報変更.png"

    runBlocking {
        val j = ocrSpaceRaw(Path.of(imgPath))

        //println(jmapper.writerWithDefaultPrettyPrinter().writeValueAsString(j))
        println(ocrSpaceParse(j))
    }
}


suspend fun watchOCR(fileEvents: ReceiveChannel<Path>, os: SendChannel<OCRResult>) {

    val imgs = Channel<Path>() //images for OCR - == preprocessed fileEvents

    coroutineScope {
        val a = launch {
            preprocessImages(fileEvents, imgs)
        }
        val b = launch {
            for (i in imgs) safely {
                os.send(ocr(i))
            }
        }

        a.join()
        b.join()
    }
}


//preprocess file events into images
// - ugh, in the end: really: just prevent the same path repeatedly
// + will delay after waiting(=new event - might still be happening)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun preprocessImages(fe: ReceiveChannel<Path>, imgs: SendChannel<Path>) {
    //fe == fileEvents
    var last = Path.of(".")

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun step(p: Path) {

        //val p1 = p.normalize().toAbsolutePath()

        //@Suppress("BlockingMethodInNonBlockingContext")
        if (Files.isSameFile(p, last)) {
            //pass: was just being updated ...
        } else {
            last = p
            imgs.send(p)
        }
    }

    while (!fe.isClosedForReceive) safely {
        val p = fe.receive()
        delay(100) //give other process time to finish writing file

        step(p)

        var immediate = true
        while (immediate) {
            immediate = select {
                fe.onReceive {
                    step(it)
                    true
                }

                onTimeout(0) { false }
            }
        }

    }
}

fun watchFolderLaunch(fileEvents: SendChannel<Path>): Thread =
    thread(name = "watchFolder") {
        while (true) safely {
            watchFolder(pathWatch) { fileEvents.sendBlocking(it) }
        }
    }


fun watchFolder(path: String, cb: (Path) -> Unit): Nothing {
    val watchService = FileSystems.getDefault().newWatchService()

    val p = Path.of(path)
    val pathKey = p.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)

    while (true) {
        val watchKey = watchService.take()

        @Suppress("UNCHECKED_CAST")
        for (event in watchKey.pollEvents()) {
            (event as? WatchEvent<Path>)?.context()?.let {
                cb(p.resolve(it))
            }
        }

        if (!watchKey.reset()) {
            watchKey.cancel()
            watchService.close()
            pathKey.cancel()
            throw IllegalStateException("watchService closed")
        }
    }
}

//ENTRY to ocr
// - impl can change
suspend fun ocr(imgPath: Path): OCRResult = ocrSpaceParse(ocrSpaceRaw(imgPath))


val okClient = OkHttpClient.Builder().build()

//val mediaTextPlain = "text/plain".toMediaType()
val mediaPng = "image/png".toMediaType()

val spaceOcrApiKey = System.getenv("OCR_SPACE_APIKEY") ?: "helloworld"
fun ocrSpaceRequest(imgPath: Path): Request {
    //FROM: https://ocr.space/OCRAPI

    val base = "https://api.ocr.space/parse/image"
    //val mediaType = "text/plain".toMediaType()
    val file = imgPath.toFile()

    val body: RequestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("language", "jpn")
        .addFormDataPart("filetype", "PNG")
        .addFormDataPart("scale", "true") //seems like a better default
        .addFormDataPart("OCREngine", "1")
        .addFormDataPart("file", file.name, file.asRequestBody(mediaPng))

        //.addFormDataPart("isOverlayRequired", "false")
        //.addFormDataPart("url", "http://dl.a9t9.com/ocrbenchmark/eng.png")
        //.addFormDataPart("iscreatesearchablepdf", "false")
        //.addFormDataPart("isSearchablePdfHideTextLayer", "false")
        .build()

    return Request.Builder()
        .url(base)
        .post(body)
        .addHeader("apikey", spaceOcrApiKey)
        .build()
}

suspend fun ocrSpaceRaw(imgPath: Path): JsonNode {

    val response: Response = okClient.newCall(ocrSpaceRequest(imgPath)).await()

    //TODO: parse response

    val b = response.body ?: throw UnexpectedException("ocr response: no body")

    @Suppress("BlockingMethodInNonBlockingContext")
    return jmapper.readTree(b.charStream())
}

fun ocrSpaceParse(res: JsonNode): OCRResult {
    val x = res["ParsedResults"]
        .elements()
        .asSequence()
        .flatMap {
            it["ParsedText"]
                .asText("")
                .lineSequence()
        }
        .filter { it.isNotBlank() }
    //TODO: handle ERR response
    return OCRResult(x.toList())
}


suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            // Don't bother with resuming the continuation if it is already cancelled.
            if (continuation.isCancelled) return

            continuation.resumeWithException(e)
        }
    })

    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (ex: Throwable) {
            //Ignore cancel exception
        }
    }
}
