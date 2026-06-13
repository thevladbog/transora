package ru.transora.app.documents

import ru.transora.sales.domain.Ticket
import java.util.zip.CRC32

object TicketQrPayload {
    fun build(ticket: Ticket): String {
        val tid = ticket.id.toString()
        val trip = ticket.tripId.toString()
        val seat = ticket.seatNumber
        val cs = crc32Short("$tid$trip$seat")
        return """{"v":1,"tid":"$tid","trip":"$trip","seat":$seat,"cs":"$cs"}"""
    }

    fun crc32Short(input: String): String {
        val crc = CRC32()
        crc.update(input.toByteArray(Charsets.UTF_8))
        return crc.value.toString(16).takeLast(4)
    }
}
