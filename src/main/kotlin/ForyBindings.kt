package org.example

data class EmptyRequest(val dummy: String = "") {}

data class MessageProcessingRequest(val msg: String = "") {}

data class BeamDetectionResponse(val interrupted: Boolean)

data class StatisticsRequest(val nScans : Int, val nAssemblies : Int, val productsCompleted: Int, val jobDone : Boolean)

data class PhotoScanResponse(val validObject : Boolean)

data class PickupResponse(val success: Boolean)

data class AssembleResponse(val success: Boolean)