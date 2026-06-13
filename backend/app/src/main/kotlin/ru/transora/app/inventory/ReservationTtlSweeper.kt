package ru.transora.app.inventory

import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile("!test")
class ReservationTtlSweeper(
    private val reservationService: ReservationService,
) {
    @Scheduled(fixedDelayString = "\${transora.inventory.reservation-sweep-interval-ms:30000}")
    @Transactional
    fun sweepExpiredReservations() {
        reservationService.expireOverdueReservations()
    }
}
