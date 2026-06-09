package org.example

import at.ac.uibk.dps.cirrina.csm.Csml
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.config.Language
import org.apache.fory.memory.MemoryBuffer
import java.util.concurrent.ThreadLocalRandom
import kotlin.use

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main()
{
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
}