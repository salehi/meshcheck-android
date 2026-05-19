package io.meshcheck.contributor

import android.content.Context
import io.meshcheck.contributor.service.AndroidTaskGateway
import io.meshcheck.contributor.service.ContributionPrefs
import io.meshcheck.data.CredentialStore
import io.meshcheck.data.earnings.EarningsRepository
import io.meshcheck.data.earnings.FakeEarningsRepository
import io.meshcheck.data.enrollment.Enroller
import io.meshcheck.data.enrollment.EnrollmentService
import io.meshcheck.data.enrollment.FakeEnrollmentService
import io.meshcheck.protocol.AgentClient
import io.meshcheck.protocol.AgentConfig
import io.meshcheck.protocol.TaskGateway

/**
 * The app's manual dependency-injection root. One instance lives for the
 * process lifetime in [MeshCheckApplication]; the UI and the foreground
 * service both read their collaborators from here, so they share one
 * [AgentClient] and one [CredentialStore].
 */
class AppContainer(context: Context) {

    val credentialStore: CredentialStore = CredentialStore(context)

    // Stubbed until the platform enrollment-redeem endpoint exists.
    val enrollmentService: EnrollmentService = FakeEnrollmentService()
    val enroller: Enroller = Enroller(enrollmentService, credentialStore)

    val contributionPrefs: ContributionPrefs = ContributionPrefs(context)

    // Stubbed until the app can call the platform accruals API (see CLAUDE.md).
    val earningsRepository: EarningsRepository = FakeEarningsRepository()

    private val taskGateway: TaskGateway = AndroidTaskGateway(credentialStore)

    val agentClient: AgentClient = AgentClient(
        config = AgentConfig(agentVersion = BuildConfig.VERSION_NAME),
        gateway = taskGateway,
    )
}
