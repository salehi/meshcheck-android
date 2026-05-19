package io.meshcheck.data.earnings

/**
 * A stand-in [EarningsRepository] used until the app can call the platform
 * accruals API for real. Reports a zero balance so the screen has a concrete,
 * honest figure to show during development.
 */
class FakeEarningsRepository : EarningsRepository {

    override suspend fun lifetimeEarnings(): Earnings = Earnings(amount = 0.0, currency = "USD")
}
