package org.example

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import com.sun.net.httpserver.HttpServer
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.pubsub.Publisher
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.use

val executorService : ScheduledExecutorService = Executors.newScheduledThreadPool(8)
val zenohConfig = Config.default()
val zenohSession = Zenoh.open(zenohConfig).getOrThrow()
const val startBeamInterruptionTopic = "eBeamInterruptedStart"
const val endBeamInterruptionTopic = "eBeamInterruptedEnd"
const val photoCapturedTopic = "ePhotoCaptured"
const val photoScannedTopic = "ePhotoScanned"
const val armPickupTopic = "eUpdatePickupStatus"
const val assemblyTopic = "eCheckAssembleSuccess"
const val armResetTopic = "eResetArm"
const val objectDisposalTopic = "eObjectDiscarded"
val zenohStartPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$startBeamInterruptionTopic").getOrThrow()).getOrThrow()
val zenohEndPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$endBeamInterruptionTopic").getOrThrow()).getOrThrow()
val zenohPhotoCapturePublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$photoCapturedTopic").getOrThrow()).getOrThrow()
val zenohPhotoScanPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$photoScannedTopic").getOrThrow()).getOrThrow()
val zenohArmPickupPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$armPickupTopic").getOrThrow()).getOrThrow()
val zenohAssemblyPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$assemblyTopic").getOrThrow()).getOrThrow()
val zenohArmResetPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$armResetTopic").getOrThrow()).getOrThrow()
val zenohObjectDisposalPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom("events/peripheral/$objectDisposalTopic").getOrThrow()).getOrThrow()

val ortEnv : OrtEnvironment = OrtEnvironment.getEnvironment()
val ortSession : OrtSession = ortEnv.createSession("models/yolov8n.onnx", OrtSession.SessionOptions())
val logger = LoggerFactory.getLogger("org.example.MainKt")

//Config Vars
const val PART_ARRIVAL_RATE_PER_SEC : Double = 10.0
const val BELT_MOVEMENT_TIME_MS : Long = 400
const val PHOTOCAPTURE_TIME_MS : Long = 500
const val PHOTOSCAN_TIME_MS : Long = 700
const val VALID_OBJ_PROB : Double = 0.99
const val PICKUP_MIN_FAILURE_PROB : Double = 0.01
const val PICKUP_MAX_FAILURE_PROB : Double = 0.25
const val ASSEMBLY_MIN_FAILURE_PROB : Double = 0.1
const val ASSEMBLY_MAX_FAILURE_PROB : Double = 0.40
const val PICKUP_TIME_MS : Long = 100L //Pickup time based on - https://www.yaskawa.fr/applications/par-applications/application/pick-place_a10963?utm_source=chatgpt.com
const val ASSEMBLY_TIME_MS : Long = 2000L
const val ARM_RESET_TIME_MS : Long = 500
val validObjectImageNames : Array<String> = arrayOf("test.png", "test2.png", "test5.png", "test6.png")
val invalidObjectImageNames : Array<String> = arrayOf("test3.png", "test4.png", "test7.png", "test8.png")
const val EVENT_RETRY_TIMEOUT_MS : Long = 10000

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main() {

    val httpServer = HttpServer.create(InetSocketAddress(6000), 0)

    val endBeamLastUnackedRequest = AtomicLong(0)
    httpServer.createContext("/movebelt") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        val currRequestId = endBeamLastUnackedRequest.updateAndGet({curr ->
            if(curr == Long.MAX_VALUE) 0
            else curr + 1
        })

        executorService.schedule({
            val endBeamInterruptedEvent = Event(endBeamInterruptionTopic, EventChannel.PERIPHERAL, data=listOf(ContextVariable("id", currRequestId)))

            emitEventWithRetry(endBeamInterruptedEvent, zenohEndPublisher, currRequestId, endBeamLastUnackedRequest)
        }, BELT_MOVEMENT_TIME_MS, TimeUnit.MILLISECONDS)
    }

    httpServer.createContext("/stopbelt") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }
    }

    httpServer.createContext("/takephoto") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            try {
                val photoCaptureEvent = Event(photoCapturedTopic, EventChannel.PERIPHERAL, data=mutableListOf())

                val rand = ThreadLocalRandom.current().nextDouble()
                if(rand <= VALID_OBJ_PROB)
                    (photoCaptureEvent.data as MutableList<ContextVariable>).add(ContextVariable("data", Files.readAllBytes(Paths.get("imgs", "valid", validObjectImageNames[(rand*100).toInt() % 4]))))
                else
                    (photoCaptureEvent.data as MutableList<ContextVariable>).add(ContextVariable("data", Files.readAllBytes(Paths.get("imgs", "invalid", invalidObjectImageNames[(rand*100).toInt() % 4]))))

                emitEvent(photoCaptureEvent, zenohPhotoCapturePublisher)
            }
            catch(exe : Exception) {
                logger.error("Failed to take photo", exe)
            }

        }, PHOTOCAPTURE_TIME_MS, TimeUnit.MILLISECONDS)
    }

    httpServer.createContext("/scanphoto") { exchange ->
        exchange.use {
            try {
                val input = Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())
                if(input.isEmpty() || input[0].name != "imgData")
                    throw IllegalArgumentException("Invalid input")

                val imgData = input[0].value as? ByteArray ?: throw IllegalArgumentException("Invalid input")

                executorService.schedule({
                    try {
                        val validObj = detectPart(imgData, intArrayOf(640,640), ortEnv, ortSession)
                        val photoScanEvent = Event(photoScannedTopic, EventChannel.PERIPHERAL, data = listOf(ContextVariable("validObject", validObj)))
                        emitEvent(photoScanEvent, zenohPhotoScanPublisher)
                    }
                    catch(exe : Exception) {
                        logger.error("Failed to scan photo", exe)
                    }
                }, PHOTOSCAN_TIME_MS, TimeUnit.MILLISECONDS)

                exchange.sendResponseHeaders(200,-1)
            }
            catch(exe : IllegalArgumentException)
            {
                logger.error("Failed to scan photo", exe)
                exchange.sendResponseHeaders(400,-1)
            }
            catch(exe : Exception) {
                logger.error("Failed to scan photo", exe)
                exchange.sendResponseHeaders(500,-1)
            }

            exchange.sendResponseHeaders(200,-1)
        }
    }

    val pickupOpsCount = AtomicLong(0)
    httpServer.createContext("/pickup") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            val opCount = pickupOpsCount.updateAndGet { curr ->
                if(curr == Long.MAX_VALUE) curr
                else curr + 1
            }

            val failureProb = getOperationWeibullFailureProb(PICKUP_MIN_FAILURE_PROB, PICKUP_MAX_FAILURE_PROB, opCount)
            val rand = ThreadLocalRandom.current().nextDouble()
            val pickupSuccess = rand >= failureProb

            val pickupEvent = Event(armPickupTopic, EventChannel.PERIPHERAL, data=listOf(ContextVariable("success", pickupSuccess)))
            emitEvent(pickupEvent, zenohArmPickupPublisher)
        }, PICKUP_TIME_MS, TimeUnit.MILLISECONDS)
    }

    val assemblyOpsCount = AtomicLong(0)
    httpServer.createContext("/assemble") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            val opCount = assemblyOpsCount.updateAndGet { curr ->
                if(curr == Long.MAX_VALUE) curr
                else curr + 1
            }

            val failureProb = getOperationWeibullFailureProb(ASSEMBLY_MIN_FAILURE_PROB, ASSEMBLY_MAX_FAILURE_PROB, opCount)
            val rand = ThreadLocalRandom.current().nextDouble()
            val assemblySuccess = rand >= failureProb

            val assemblyEvent = Event(assemblyTopic, EventChannel.PERIPHERAL, data=listOf(ContextVariable("success", assemblySuccess)))
            emitEvent(assemblyEvent, zenohAssemblyPublisher)
        }, ASSEMBLY_TIME_MS, TimeUnit.MILLISECONDS)

    }

    val armResetLastUnackedRequest = AtomicLong(0)
    httpServer.createContext("/returntostart") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        val currRequestId = armResetLastUnackedRequest.updateAndGet({curr ->
            if(curr == Long.MAX_VALUE) 0
            else curr + 1
        })

        executorService.schedule({
            val armResetEvent = Event(armResetTopic, EventChannel.PERIPHERAL, data = listOf(ContextVariable("success", true)))
            emitEventWithRetry(armResetEvent, zenohArmResetPublisher, currRequestId, armResetLastUnackedRequest)
        }, ARM_RESET_TIME_MS, TimeUnit.MILLISECONDS)
    }

    httpServer.createContext("/process/email") { exchange ->
        exchange.use { exchange ->
            val cv =
                Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())[0]
            exchange.sendResponseHeaders(200, -1)
            logger.info("\nSending email with msg: ${cv.value as String}")
        }
    }

    httpServer.createContext("/process/sms") { exchange ->
        exchange.use { exchange ->
            val cv =
                Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())[0]
            exchange.sendResponseHeaders(200, -1)
            logger.info("\nSending sms with msg: ${cv.value as String}")
        }
    }

    httpServer.createContext("/statistics") { exchange ->
        var reqData : List<ContextVariable> = emptyList()
        exchange.use { exchange ->
            reqData = Serializer.deserialize(exchange.requestBody.readAllBytes())
            exchange.sendResponseHeaders(200, -1)
        }

        val statisticsSb = buildString {
            append("\nReceived statistics: ")
            reqData.forEach { cv ->
                append("\n${cv.name} = ${cv.value}")
            }
        }
        logger.info(statisticsSb)

        val nScans = reqData.filter { cv -> cv.name == "nScans" }[0]
        val nAssemblies = reqData.filter { cv -> cv.name == "nAssemblies" }[0]
        if((nScans.value as Int) < (nAssemblies.value as Int)) logger.warn("Statistical discrepancy")
    }

    val objectDiscardLastUnackedRequest = AtomicLong(0)
    httpServer.createContext("/discardobject") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        val currRequestId = objectDiscardLastUnackedRequest.updateAndGet({curr ->
            if(curr == Long.MAX_VALUE) 0
            else curr + 1
        })

        executorService.schedule({
            val objectDisposalEvent = Event(objectDisposalTopic, EventChannel.PERIPHERAL, data = listOf(ContextVariable("success", true)))
            emitEventWithRetry(objectDisposalEvent, zenohObjectDisposalPublisher, currRequestId, objectDiscardLastUnackedRequest)
        }, BELT_MOVEMENT_TIME_MS, TimeUnit.MILLISECONDS)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        shutdown(httpServer)
    })

    httpServer.start()
    logger.info("Http Server Started at http://localhost:6000")

    val arrivalTime = getNextArrivalTime(PART_ARRIVAL_RATE_PER_SEC)
    executorService.schedule({
        emitStartBeam()
    }, (arrivalTime*1000.0).roundToLong(), TimeUnit.MILLISECONDS)

}

fun emitStartBeam()
{
    try {
        val beamInterruptedStartEvent = Event(startBeamInterruptionTopic, EventChannel.PERIPHERAL, data = listOf(
            ContextVariable("detected", true)
        ), target = "assemblyController")
        val eventPayload = ZBytes.from(Serializer.serialize(beamInterruptedStartEvent))

        zenohStartPublisher.put(eventPayload).onFailure { exe -> logger.error("failed to send event '$beamInterruptedStartEvent'", exe) }

        //Scheduling next beam
        val nextArrivalTime = getNextArrivalTime(PART_ARRIVAL_RATE_PER_SEC)
        executorService.schedule({
            emitStartBeam()
        }, (nextArrivalTime*1000).roundToLong(), TimeUnit.MILLISECONDS)
    }
    catch(exe : Exception) {
        logger.error(exe.message, exe)
    }
}

fun emitEvent(event : Event, publisher : Publisher)
{
    val eventPayload = ZBytes.from(Serializer.serialize(event))

    publisher.put(eventPayload).onFailure { exe -> logger.error("failed to send event '$event'", exe) }
}

fun emitEventWithRetry(event : Event, publisher : Publisher, eventId : Long, lastUnackedRequest : AtomicLong, retryTimeoutMs: Long = EVENT_RETRY_TIMEOUT_MS)
{
    if(lastUnackedRequest.get() == eventId)
    {
        emitEvent(event, publisher)
        executorService.schedule({
            emitEventWithRetry(event, publisher, eventId, lastUnackedRequest)
        },  retryTimeoutMs, TimeUnit.MILLISECONDS)
    }
}

fun detectPart(imgData : ByteArray, onnxInputDims : IntArray, env : OrtEnvironment, session : OrtSession, confThreshold : Float = 0.25f) : Boolean
{
    try {
        val inputWidth = onnxInputDims[0]
        val inputHeight = onnxInputDims[1]

        val img = ImageIO.read(ByteArrayInputStream(imgData))

        val resized = letterboxImage(img, inputWidth, inputHeight)
        val chwfTensor = toCHWFTensor(resized)

        val inputName = session.inputNames.iterator().next()

        OnnxTensor.createTensor(env, FloatBuffer.wrap(chwfTensor), longArrayOf(1, 3, inputWidth.toLong(), inputHeight.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val detections = outputs[0].value as Array<Array<FloatArray>>

                for(detection in detections[0])
                {
                    if(detection.size < 6) continue

                    val conf = detection[4]
                    if(conf < confThreshold) continue

                    val classId = detection[5]
                    if(classId == 39f) return true
                }
            }
        }
    }
    catch(exe : Exception)
    {
        logger.error("Failed to detect object", exe)
    }

    return false
}

fun toCHWFTensor(img : BufferedImage) : FloatArray
{
    val tensor = FloatArray(3 * img.width * img.height)

    val offsets = intArrayOf(0, img.width * img.height, 2 * img.width * img.height)

    for (y in 0 until img.height)
    {
        for (x in 0 until img.width)
        {
            val rgb = img.getRGB(x, y)
            val r = ((rgb shr 16) and 0xFF) / 255.0f
            val g = ((rgb shr 8) and 0xFF) / 255.0f
            val b = (rgb and 0xFF) / 255.0f

            tensor[offsets[0]++] = r
            tensor[offsets[1]++] = g
            tensor[offsets[2]++] = b
        }
    }

    return tensor
}

fun letterboxImage(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
    val scale = min(
        targetW.toDouble() / src.width.toDouble(),
        targetH.toDouble() / src.height.toDouble()
    )

    val newW = (src.width * scale).roundToInt()
    val newH = (src.height * scale).roundToInt()

    val resizedTmp = src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH)
    val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)

    val g: Graphics2D = out.createGraphics()
    g.color = Color(114, 114, 114)
    g.fillRect(0, 0, targetW, targetH)

    val x = (targetW - newW) / 2
    val y = (targetH - newH) / 2
    g.drawImage(resizedTmp, x, y, null)
    g.dispose()

    return out
}

fun shutdown(httpServer : HttpServer) {
    try {
        httpServer.stop(0)
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown http server", exe)
    }

    try {
        executorService.shutdown()
        if(!executorService.awaitTermination(5, TimeUnit.SECONDS))
            executorService.shutdownNow()
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown executor service", exe)
        executorService.shutdownNow()
    }

    try {
        zenohStartPublisher.close()
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown zenoh start publisher", exe)
    }

    try {
        zenohEndPublisher.close()
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown zenoh end publisher", exe)
    }

    try {
        zenohSession.close()
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown zenoh session", exe)
    }

    try {
        ortSession.close()
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown ort session", exe)
    }

    try {
        ortEnv.close()
    }
    catch(exe : Exception)
    {
        logger.error("Failed to shutdown ort environment", exe)
    }
}

fun getNextArrivalTime(arrivalRate : Double) : Double
{
    val expo = ThreadLocalRandom.current().nextExponential()
    return (expo / arrivalRate)
}

fun getOperationWeibullFailureProb(minFailureProb : Double, maxFailureProb : Double, operation : Long, shape : Double = 3.0, scale : Double = 100.0) : Double
{
    //Monotonically increasing Weibull-shaped probability

    return minFailureProb + (maxFailureProb - minFailureProb) * (1 - exp(-(operation.toDouble() / scale).pow(shape)))
}