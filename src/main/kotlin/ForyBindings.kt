package org.example

data class EmptyRequest(val dummy: String = "") {}

data class MessageProcessingRequest(val msg: String = "") {}

data class StatisticsRequest(val nScans : Int, val nAssemblies : Int, val productsCompleted: Int, val jobDone : Boolean)

data class PhotoScanRequest(val photoData : ByteArray)