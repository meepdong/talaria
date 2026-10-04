package io.github.meepdong.talaria.ui

import kotlinx.serialization.Serializable

@Serializable
data class VpsCommandRequest(
    val command: String,
    val args: List<String>,
    val cwd: String,
    val timeout: Int,
    val streamOutput: Boolean,
)

sealed interface VpsCommandState {
    data class Pending(val request: VpsCommandRequest, val turnId: String) : VpsCommandState
    data class Running(val request: VpsCommandRequest, val output: List<String>) : VpsCommandState
    data class Completed(val request: VpsCommandRequest, val exitCode: Int, val output: List<String>) : VpsCommandState
    data class Failed(val request: VpsCommandRequest, val error: String) : VpsCommandState
}

@Serializable
data class VpsApprovalPayload(
    val command: String,
    val args: List<String>,
    val cwd: String,
    val timeout: Int,
    val turnId: String,
)