package org.example

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import at.ac.uibk.dps.smartfactory.api.MessageProcessingRequest
import at.ac.uibk.dps.smartfactory.api.PhotoScanRequest
import at.ac.uibk.dps.smartfactory.api.StatisticsRequest
import at.ac.uibk.dps.smartfactory.api.ForyConfig
import com.sun.net.httpserver.HttpServer
import io.dapr.client.DaprClientBuilder
import java.net.InetSocketAddress
import org.apache.fory.ThreadSafeFory
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.nio.file.Files
import java.nio.file.Paths
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

val daprClient = DaprClientBuilder().build()
const val endBeamInterruptionTopic = "eBeamInterruptedEnd"
const val photoCapturedTopic = "ePhotoCaptured"
const val photoScannedTopic = "ePhotoScanned"
const val armPickupTopic = "eUpdatePickupStatus"
const val assemblyTopic = "eCheckAssembleSuccess"
const val armResetTopic = "eResetArm"
const val objectDisposalTopic = "eObjectDiscarded"

val ortEnv : OrtEnvironment = OrtEnvironment.getEnvironment()
val ortSession : OrtSession = ortEnv.createSession("models/yolov8n.onnx", OrtSession.SessionOptions())
val logger = LoggerFactory.getLogger("org.example.MainKt")

//Config Vars
const val BELT_MOVEMENT_TIME_MS : Long = 400
const val PHOTOCAPTURE_TIME_MS : Long = 500
const val PHOTOSCAN_TIME_MS : Long = 700
const val VALID_OBJ_PROB : Double = 1.0
const val PICKUP_MIN_FAILURE_PROB : Double = 0.0
const val PICKUP_MAX_FAILURE_PROB : Double = 0.0
const val ASSEMBLY_MIN_FAILURE_PROB : Double = 0.0
const val ASSEMBLY_MAX_FAILURE_PROB : Double = 0.0
const val PICKUP_TIME_MS : Long = 100L
const val ASSEMBLY_TIME_MS : Long = 1000L
const val ARM_RESET_TIME_MS : Long = 500
val validObjectImageNames : Array<String> = arrayOf("test.png", "test2.png", "test5.png", "test6.png")
val invalidObjectImageNames : Array<String> = arrayOf("test3.png", "test4.png", "test7.png", "test8.png")

fun main()
{
    val fory: ThreadSafeFory = ForyConfig.fory

    val httpServer = HttpServer.create(InetSocketAddress(6000), 0)

    httpServer.createContext("/movebelt") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            daprClient.publishEvent("pubsub", endBeamInterruptionTopic, mapOf<String,Any>()).subscribe()
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
                var imgData : ByteArray = byteArrayOf()

                val rand = ThreadLocalRandom.current().nextDouble()
                if(rand <= VALID_OBJ_PROB)
                    imgData = Files.readAllBytes(Paths.get("imgs", "valid", validObjectImageNames[(rand*100).toInt() % 4]))
                else
                    imgData = Files.readAllBytes(Paths.get("imgs", "invalid", invalidObjectImageNames[(rand*100).toInt() % 4]))

                val response = mapOf("data" to imgData)
                daprClient.publishEvent("pubsub", photoCapturedTopic, response).subscribe()
            }
            catch(exe : Exception) {
                logger.error("Failed to take photo", exe)
            }

        }, PHOTOCAPTURE_TIME_MS, TimeUnit.MILLISECONDS)
    }

    httpServer.createContext("/scanphoto") { exchange ->
        exchange.use {
            try {
                val request = fory.deserialize(exchange.requestBody.readAllBytes()) as PhotoScanRequest
                val input = request.photoData
                if(input.isEmpty())
                    throw IllegalArgumentException("Invalid input")

                executorService.schedule({
                    try {
                        val validObj = detectPart(input, intArrayOf(640,640), ortEnv, ortSession)
                        daprClient.publishEvent("pubsub", photoScannedTopic, mapOf("validObject" to validObj)).subscribe()
                    }
                    catch(exe : Exception) {
                        logger.error("Failed to scan photo", exe)
                    }
                }, PHOTOSCAN_TIME_MS, TimeUnit.MILLISECONDS)
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

            daprClient.publishEvent("pubsub", armPickupTopic, pickupSuccess).subscribe()
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

            daprClient.publishEvent("pubsub", assemblyTopic, assemblySuccess).subscribe()
        }, ASSEMBLY_TIME_MS, TimeUnit.MILLISECONDS)

    }

    httpServer.createContext("/returntostart") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            daprClient.publishEvent("pubsub", armResetTopic, mapOf<String,Any>()).subscribe()
        }, ARM_RESET_TIME_MS, TimeUnit.MILLISECONDS)
    }

    httpServer.createContext("/process/email") { exchange ->
        exchange.use { exchange ->
            val data = fory.deserialize(exchange.requestBody.readAllBytes()) as MessageProcessingRequest
            exchange.sendResponseHeaders(200, -1)
            logger.info("\nSending email with msg: ${data.msg}")
        }
    }

    httpServer.createContext("/process/sms") { exchange ->
        exchange.use { exchange ->
            val data = fory.deserialize(exchange.requestBody.readAllBytes()) as MessageProcessingRequest
            exchange.sendResponseHeaders(200, -1)
            logger.info("\nSending sms with msg: ${data.msg}")
        }
    }

    httpServer.createContext("/statistics") { exchange ->
        var reqData : StatisticsRequest? = null
        exchange.use { exchange ->
            reqData = fory.deserialize(exchange.requestBody.readAllBytes()) as StatisticsRequest
            exchange.sendResponseHeaders(200, -1)
        }

        logger.info("\nReceived statistics: ")
        logger.info("nScans = ${reqData?.nScans ?: -1}")
        logger.info("nAssemblies = ${reqData?.nAssemblies ?: -1}")
        logger.info("Products Completed = ${reqData?.productsCompleted ?: -1}")
        logger.info("Job Done = ${reqData?.jobDone ?: -1}")
    }

    httpServer.createContext("/discardobject") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            daprClient.publishEvent("pubsub", objectDisposalTopic, mapOf<String,Any>()).subscribe()
        }, BELT_MOVEMENT_TIME_MS, TimeUnit.MILLISECONDS)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        shutdown(httpServer)
    })

    httpServer.start()
    logger.info("Http Server Started at http://localhost:6000")
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

fun getOperationWeibullFailureProb(minFailureProb : Double, maxFailureProb : Double, operation : Long, shape : Double = 3.0, scale : Double = 100.0) : Double
{
    //Monotonically increasing Weibull-shaped probability

    return minFailureProb + (maxFailureProb - minFailureProb) * (1 - exp(-(operation.toDouble() / scale).pow(shape)))
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
