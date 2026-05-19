package io.meshcheck.contributor.service

import io.meshcheck.checks.CheckRunner
import io.meshcheck.data.CredentialStore
import io.meshcheck.protocol.TaskGateway
import meshcheck.agent.v1.ResultSubmit
import meshcheck.agent.v1.TaskAssignment
import okio.ByteString

/**
 * The [TaskGateway] implementation that ties the layers together: it runs the
 * task's check (`:checks`), signs the measurements with the Node's Ed25519 key
 * (`:data`), and assembles the `ResultSubmit` for the protocol layer to send.
 */
class AndroidTaskGateway(
    private val credentialStore: CredentialStore,
) : TaskGateway {

    override suspend fun runTask(task: TaskAssignment): ResultSubmit {
        val startedAt = System.currentTimeMillis()
        val result = CheckRunner.run(
            checkType = task.check_type,
            target = task.target,
            parametersJson = task.parameters.toByteArray(),
        )
        val completedAt = System.currentTimeMillis()

        // Sign exactly the measurement bytes that go on the wire.
        val signature = credentialStore.signResult(
            taskId = task.task_id,
            checkId = task.check_id,
            outcome = result.outcome,
            measurements = result.measurementsJson,
            startedAt = startedAt,
            completedAt = completedAt,
        )

        return ResultSubmit(
            task_id = task.task_id,
            check_id = task.check_id,
            outcome = result.outcome,
            measurements = ByteString.of(*result.measurementsJson),
            started_at = startedAt,
            completed_at = completedAt,
            signature = ByteString.of(*signature),
        )
    }
}
