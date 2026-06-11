package io.meshcheck.contributor

import android.content.Context
import android.os.Build
import io.meshcheck.checks.IcmpCapability
import io.meshcheck.contributor.service.AndroidTaskGateway
import io.meshcheck.contributor.service.ContributionPrefs
import io.meshcheck.data.CredentialStore
import io.meshcheck.data.enrollment.Enroller
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

    // Enrollment is local: the scanned QR carries the gateway URL and the
    // device-enrollment JWT, which is the gateway credential itself (no redeem).
    val enroller: Enroller = Enroller(credentialStore)

    val contributionPrefs: ContributionPrefs = ContributionPrefs(context)

    private val taskGateway: TaskGateway = AndroidTaskGateway(credentialStore)

    // Advertise `ping` only when this device can open an unprivileged ICMP
    // socket; some OEMs lock down net.ipv4.ping_group_range. The platform then
    // never assigns a ping task to a device that cannot run it.
    private val canSendIcmp: Boolean = IcmpCapability.canSendIcmp()

    val agentClient: AgentClient = AgentClient(
        config = AgentConfig(
            agentVersion = BuildConfig.VERSION_NAME,
            supportedCheckTypes = buildList {
                add("http"); add("tcp"); add("dns")
                if (canSendIcmp) add("ping")
            },
            canSendIcmp = canSendIcmp,
            // Self-declared, human-readable label (Capabilities.name); the
            // device model is the closest thing we have without app settings.
            nodeName = Build.MODEL.orEmpty(),
        ),
        gateway = taskGateway,
    )
}
