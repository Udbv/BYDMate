package com.bydmate.app.domain.cost

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one kWh of a trip starting at a moment really cost: the weighted price of the energy in
 * the pack after the charges before it (the `charges` rule, whatever rule the settings use), with
 * its DC share. Without a charge in the last 60 days, the home rate and an unknown share.
 */
@Singleton
class BatteryPriceLookup @Inject constructor(
    private val costCalculator: CostCalculator,
    private val chargeDao: ChargeDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun at(ts: Long): TripEnergyPrice? {
        val period = costCalculator.schedule().periodAt(ts) ?: return null
        val timeline = BatteryPriceTimeline(chargeDao.getCompletedForPricing(ts), settingsRepository.getBatteryCapacity())
        val priced = timeline.tripPriceAt(ts)
        return TripEnergyPrice(priced?.pricePerKwh ?: period.homeRate, priced?.dcShare)
    }
}
