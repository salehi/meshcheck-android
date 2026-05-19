package io.meshcheck.data.earnings

/** The contributor's lifetime earnings, as shown on the contributor screen. */
data class Earnings(
    val amount: Double,
    val currency: String,
)

/**
 * Supplies the lifetime-earnings figure, which on the platform comes from
 * `GET /v1/organizations/{id}/accruals`.
 *
 * Stubbed in v1 ([FakeEarningsRepository]): the real call needs the
 * `organization_id`, which the (also stubbed) enrollment flow does not yet
 * return. This interface is the seam for the real client once both contracts
 * are settled with the platform team.
 */
interface EarningsRepository {

    /** The lifetime earnings, or null if the figure is not available yet. */
    suspend fun lifetimeEarnings(): Earnings?
}
