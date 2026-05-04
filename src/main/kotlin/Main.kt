package org.example

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.config.Language
import org.apache.fory.memory.MemoryBuffer
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.use

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main()
{
    val fory: ThreadSafeFory =
        Fory.builder().withLanguage(Language.XLANG).withRefTracking(true).buildThreadSafeFory().apply {
            register(EmptyRequest::class.java)
            register(BeamDetectionResponse::class.java)
            register(StatisticsRequest::class.java)
            register(MessageProcessingRequest::class.java)
            register(PhotoScanResponse::class.java)
            register(PickupResponse::class.java)
            register(AssembleResponse::class.java)
        }

    val threadBuffer = ThreadLocal.withInitial { MemoryBuffer.newHeapBuffer(1024) }

    val httpServer = HttpServer.create(InetSocketAddress(6000), 0)

    httpServer.createContext("/movebelt") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }
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
    }

    httpServer.createContext("/scanphoto") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val response = PhotoScanResponse(rand <= 60)
            if(rand > 60) println("/scanphoto failed")

            val buffer = threadBuffer.get().apply { writerIndex(0) }
            fory.serialize(buffer, response)

            exchange.sendResponseHeaders(200, buffer.writerIndex().toLong())
            exchange.responseBody.use { stream -> stream.write(buffer.getBytes(0, buffer.writerIndex())) }
        }
    }

    httpServer.createContext("/detectbeam/start") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val response = BeamDetectionResponse(rand <= 50)
            if(rand > 50) println("/detectbeam/start failed")

            val buffer = threadBuffer.get().apply { writerIndex(0) }
            fory.serialize(buffer, response)

            exchange.sendResponseHeaders(200, buffer.writerIndex().toLong())
            exchange.responseBody.use { stream -> stream.write(buffer.getBytes(0, buffer.writerIndex())) }
        }
    }

    httpServer.createContext("/detectbeam/end") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val response = BeamDetectionResponse(rand <= 40)
            if(rand > 40) println("/detectbeam/end failed")

            val buffer = threadBuffer.get().apply { writerIndex(0) }
            fory.serialize(buffer, response)

            exchange.sendResponseHeaders(200, buffer.writerIndex().toLong())
            exchange.responseBody.use { stream -> stream.write(buffer.getBytes(0, buffer.writerIndex())) }
        }
    }

    httpServer.createContext("/pickup") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val response = PickupResponse(rand <= 80)
            if(rand > 80) println("/pickup failed")

            val buffer = threadBuffer.get().apply { writerIndex(0) }
            fory.serialize(buffer, response)

            exchange.sendResponseHeaders(200, buffer.writerIndex().toLong())
            exchange.responseBody.use { stream -> stream.write(buffer.getBytes(0, buffer.writerIndex())) }
        }
    }

    httpServer.createContext("/assemble") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val response = AssembleResponse(rand <= 75)
            if(rand > 75) println("/assemble failed")

            val buffer = threadBuffer.get().apply { writerIndex(0) }
            fory.serialize(buffer, response)

            exchange.sendResponseHeaders(200, buffer.writerIndex().toLong())
            exchange.responseBody.use { stream -> stream.write(buffer.getBytes(0, buffer.writerIndex())) }
        }
    }

    httpServer.createContext("/returntostart") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }
    }

    httpServer.createContext("/process/email") { exchange ->
        exchange.use { exchange ->
            val data = fory.deserialize(exchange.requestBody.readAllBytes()) as MessageProcessingRequest
            exchange.sendResponseHeaders(200, -1)
            println("\nSending email with msg: ${data.msg}")
        }
    }

    httpServer.createContext("/process/sms") { exchange ->
        exchange.use { exchange ->
            val data = fory.deserialize(exchange.requestBody.readAllBytes()) as MessageProcessingRequest
            exchange.sendResponseHeaders(200, -1)
            println("\nSending sms with msg: ${data.msg}")
        }
    }

    httpServer.createContext("/statistics") { exchange ->
        var reqData : StatisticsRequest? = null
        exchange.use { exchange ->
            reqData = fory.deserialize(exchange.requestBody.readAllBytes()) as StatisticsRequest
            exchange.sendResponseHeaders(200, -1)
        }

        println("\nReceived statistics: ")
        println("nScans = ${reqData?.nScans ?: -1}")
        println("nAssemblies = ${reqData?.nAssemblies ?: -1}")
        println("Products Completed = ${reqData?.productsCompleted ?: -1}")
        println("Job Done = ${reqData?.jobDone ?: -1}")
    }

    httpServer.start()
    println("Http Server Started at http://localhost:6000")
}