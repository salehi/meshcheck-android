package io.meshcheck.protocol

import meshcheck.agent.v1.ResultSubmit
import meshcheck.agent.v1.TaskAssignment

/**
 * Runs a [TaskAssignment] and produces the signed [ResultSubmit] to send back.
 *
 * Implemented outside `:protocol` — the foreground service wires the check
 * executors (`:checks`) and the Ed25519 signer (`:data`) into it — so
 * `:protocol` itself stays free of any dependency on those layers.
 */
interface TaskGateway {

    /**
     * Executes [task] and returns the signed result to submit, or `null` to
     * submit nothing (e.g. the parameters could not be decoded at all, or the
     * task was cancelled mid-run).
     *
     * Called from a coroutine that is cancelled if the platform sends a
     * `TaskCancel` for this task — implementations must honor cancellation.
     */
    suspend fun runTask(task: TaskAssignment): ResultSubmit?
}
