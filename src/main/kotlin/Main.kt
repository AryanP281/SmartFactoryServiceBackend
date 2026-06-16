package org.example

import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import com.sun.net.httpserver.HttpServer
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.use

val executorService : ScheduledExecutorService = Executors.newScheduledThreadPool(1)
val zenohConfig = Config.default()
val zenohSession = Zenoh.open(zenohConfig).getOrThrow()
val startKey = "events/peripheral/eBeamInterruptedStart"
val endKey = "events/peripheral/eBeamInterruptedEnd"
val zenohStartPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom(startKey).getOrThrow()).getOrThrow()
val zenohEndPublisher = zenohSession.declarePublisher(KeyExpr.tryFrom(endKey).getOrThrow()).getOrThrow()

//Config Vars
val BELT_MOVEMENT_TIME_MS : Long = 2000

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main()
{
    val httpServer = HttpServer.create(InetSocketAddress(6000), 0)

    httpServer.createContext("/movebelt") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }

        executorService.schedule({
            val beamInterruptedEndEvent = Event("eBeamInterruptedEnd", EventChannel.PERIPHERAL, data = listOf(
                ContextVariable("detected", true)
            ), target = "assemblyController")
            val eventPayload = ZBytes.from(Serializer.serialize(beamInterruptedEndEvent))

            zenohEndPublisher.put(eventPayload).onFailure { exe -> println("failed to send event '$beamInterruptedEndEvent' - $exe") }.onSuccess { println("Successfully sent event '$beamInterruptedEndEvent'") }
        }, BELT_MOVEMENT_TIME_MS, TimeUnit.MILLISECONDS)
    }

    httpServer.createContext("/stopbelt") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }
    }

    httpServer.createContext("/takephoto") { exchange ->
        exchange.use {
            val respData =
                listOf<ContextVariable>(ContextVariable("data", 100))
            val serializedResp = Serializer.serialize(respData)

            exchange.sendResponseHeaders(200, serializedResp.size.toLong())
            exchange.responseBody.use { stream -> stream.write(serializedResp) }
        }
    }

    httpServer.createContext("/scanphoto") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val respData =
                listOf<ContextVariable>(ContextVariable("validObject", rand <= 60))
            val serializedResp = Serializer.serialize(respData)
            exchange.sendResponseHeaders(200, serializedResp.size.toLong())
            exchange.responseBody.use { stream -> stream.write(serializedResp) }
        }
    }

    httpServer.createContext("/detectbeam/start") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val respData = listOf<ContextVariable>(ContextVariable("interrupted", rand <= 50))
            val serializedResp = Serializer.serialize(respData)
            exchange.sendResponseHeaders(200, serializedResp.size.toLong())
            exchange.responseBody.use { stream -> stream.write(serializedResp) }
        }
    }

    httpServer.createContext("/detectbeam/end") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val respData = listOf<ContextVariable>(ContextVariable("interrupted", rand <= 40))
            val serializedResp = Serializer.serialize(respData)
            exchange.sendResponseHeaders(200, serializedResp.size.toLong())
            exchange.responseBody.use { stream -> stream.write(serializedResp) }
        }
    }

    httpServer.createContext("/pickup") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val respData = listOf<ContextVariable>(ContextVariable("success", rand <= 80))
            val serializedResp = Serializer.serialize(respData)

//            Thread.sleep(2000)
            exchange.sendResponseHeaders(200, serializedResp.size.toLong())
            exchange.responseBody.use { stream -> stream.write(serializedResp) }
        }
    }

    httpServer.createContext("/assemble") { exchange ->
        exchange.use {
            val rand = ThreadLocalRandom.current().nextInt(1,101)

            val respData = listOf<ContextVariable>(ContextVariable("success", rand <= 75))
            val serializedResp = Serializer.serialize(respData)

//            Thread.sleep(5000)
            exchange.sendResponseHeaders(200, serializedResp.size.toLong())
            exchange.responseBody.use { stream -> stream.write(serializedResp) }
        }
    }

    httpServer.createContext("/returntostart") { exchange ->
        exchange.use {
            exchange.sendResponseHeaders(200,-1)
        }
    }

    httpServer.createContext("/process/email") { exchange ->
        exchange.use { exchange ->
            val cv =
                Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())[0]
            exchange.sendResponseHeaders(200, -1)
            println("\nSending email with msg: ${cv.value as String}")
        }
    }

    httpServer.createContext("/process/sms") { exchange ->
        exchange.use { exchange ->
            val cv =
                Serializer.deserialize<List<ContextVariable>>(exchange.requestBody.readAllBytes())[0]
            exchange.sendResponseHeaders(200, -1)
            println("\nSending sms with msg: ${cv.value as String}")
        }
    }

    httpServer.createContext("/statistics") { exchange ->
        var reqData : List<ContextVariable> = emptyList()
        exchange.use { exchange ->
            reqData = Serializer.deserialize(exchange.requestBody.readAllBytes())
            exchange.sendResponseHeaders(200, -1)
        }

        println("\nReceived statistics: ")
        reqData.forEach { cv ->
            println("${cv.name} = ${cv.value}")
        }

    }

    httpServer.start()
    println("Http Server Started at http://localhost:6000")

    executorService.scheduleWithFixedDelay({
        try {
            val beamInterruptedStartEvent = Event("eBeamInterruptedStart", EventChannel.PERIPHERAL, data = listOf(
                ContextVariable("detected", true)
            ), target = "assemblyController")
            val eventPayload = ZBytes.from(Serializer.serialize(beamInterruptedStartEvent))

            zenohStartPublisher.put(eventPayload).onFailure { exe -> println("failed to send event '$beamInterruptedStartEvent' - $exe") }.onSuccess { println("Successfully sent event '$beamInterruptedStartEvent'") }
        }
        catch(exe : Exception) {
            println(exe)
        }

    }, 1000, 5000, TimeUnit.MILLISECONDS)

}