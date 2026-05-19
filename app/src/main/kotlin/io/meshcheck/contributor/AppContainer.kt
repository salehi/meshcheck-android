package io.meshcheck.contributor

import android.content.Context
import io.meshcheck.data.CredentialStore
import io.meshcheck.data.enrollment.Enroller
import io.meshcheck.data.enrollment.EnrollmentService
import io.meshcheck.data.enrollment.FakeEnrollmentService

/**
 * The app's manual dependency-injection root. One instance lives for the
 * process lifetime in [MeshCheckApplication]; screens and the (later)
 * foreground service read their collaborators from here.
 *
 * Kept deliberately small — the app is too simple to justify a DI framework.
 */
class AppContainer(context: Context) {

    val credentialStore: CredentialStore = CredentialStore(context)

    // Stubbed until the platform enrollment-redeem endpoint exists.
    val enrollmentService: EnrollmentService = FakeEnrollmentService()

    val enroller: Enroller = Enroller(enrollmentService, credentialStore)
}
