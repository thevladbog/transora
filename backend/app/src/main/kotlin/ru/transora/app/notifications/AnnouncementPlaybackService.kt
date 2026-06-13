package ru.transora.app.notifications

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.app.scheduling.TripFilter
import ru.transora.app.scheduling.TripRepository
import ru.transora.app.stationagent.AudioPlayPayload
import ru.transora.app.stationagent.StationAgentEventPublisher
import ru.transora.scheduling.domain.TripStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class AnnouncementPlaybackService(
    private val announcementRepository: AnnouncementRepository,
    private val ttsService: TtsService,
    private val serviceStationRepository: ServiceStationRepository,
    private val stationAgentEventPublisher: StationAgentEventPublisher,
) {
    fun processDueAnnouncements() {
        val now = Clock.systemUTC().instant()
        announcementRepository.listDue(now).forEach { process(it) }
    }

    fun process(announcement: AnnouncementRow) {
        val audioPath = announcement.audioPath ?: run {
            val result = ttsService.synthesize(announcement.textContent, announcement.id.toString())
            announcementRepository.updateAudioPath(announcement.id, result.storagePath)
            result.storagePath
        }
        val stationId = serviceStationRepository.findByCode(announcement.stationCode)?.id ?: return
        announcementRepository.updateStatus(announcement.id, "PLAYING")
        stationAgentEventPublisher.playAudio(
            stationId,
            AudioPlayPayload(
                announcementId = announcement.id.toString(),
                audioUrl = "/api/announcements/${announcement.id}/audio",
                priority = announcement.priority,
                text = announcement.textContent,
            ),
        )
        announcementRepository.updateStatus(announcement.id, "DONE")
    }

    fun getAudioContent(id: UUID): Pair<String, ByteArray>? {
        val announcement = announcementRepository.findById(id) ?: return null
        val path = announcement.audioPath ?: return null
        val file = Path.of(path)
        if (!Files.exists(file)) return null
        return "audio/wav" to Files.readAllBytes(file)
    }
}

@Component
class AnnouncementPlaybackJob(
    private val playbackService: AnnouncementPlaybackService,
) {
    @Scheduled(fixedDelayString = "\${transora.notifications.playback-poll-interval-ms:5000}")
    fun pollQueue() {
        playbackService.processDueAnnouncements()
    }
}

@Component
class AnnouncementDepartureScheduler(
    private val tripRepository: TripRepository,
    private val announcementRepository: AnnouncementRepository,
    private val templateRepository: AnnouncementTemplateRepository,
) {
    fun enqueueDepartureReminders(now: Instant = Clock.systemUTC().instant()) {
        enqueueWindow(now, minutesBefore = 30, templateCode = "DEPARTURE_30")
        enqueueWindow(now, minutesBefore = 15, templateCode = "DEPARTURE_15")
    }

    @Scheduled(cron = "\${transora.notifications.departure-scheduler-cron:0 * * * * *}")
    fun scheduledEnqueue() {
        enqueueDepartureReminders()
    }

    private fun enqueueWindow(now: Instant, minutesBefore: Int, templateCode: String) {
        val template = templateRepository.findByCode(templateCode) ?: return
        val from = now.plus((minutesBefore - 1).toLong(), ChronoUnit.MINUTES)
        val to = now.plus((minutesBefore + 1).toLong(), ChronoUnit.MINUTES)
        tripRepository.listFiltered(TripFilter(from = from, to = to, limit = 200))
            .filter { it.status in ACTIVE_DEPARTURE_STATUSES }
            .forEach { trip ->
                val stationCode = trip.departureStationCode.uppercase()
                if (!announcementRepository.recordScheduled(stationCode, trip.id, templateCode)) {
                    return@forEach
                }
                val text = template.templateText
                    .replace("{tripNumber}", trip.tripNumber ?: trip.routeNumber)
                    .replace("{platform}", trip.platform ?: "-")
                val created = Clock.systemUTC().instant()
                announcementRepository.insert(
                    AnnouncementRow(
                        id = UUID.randomUUID(),
                        stationCode = stationCode,
                        tripId = trip.id,
                        priority = template.priority,
                        textContent = text,
                        status = "QUEUED",
                        scheduledAt = created,
                        createdAt = created,
                        templateCode = templateCode,
                    ),
                )
            }
    }

    companion object {
        private val ACTIVE_DEPARTURE_STATUSES = setOf(
            TripStatus.PLANNED,
            TripStatus.OPEN,
            TripStatus.BOARDING,
        )
    }
}
