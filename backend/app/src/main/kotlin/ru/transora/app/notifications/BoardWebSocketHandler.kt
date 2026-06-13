package ru.transora.app.notifications

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class BoardWebSocketHandler(
    private val displayBoardRepository: DisplayBoardRepository,
    private val boardStateService: BoardStateService,
) : TextWebSocketHandler() {
    private val sessionsByBoard = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val boardId = extractBoardId(session)
            ?: run {
                session.close(CloseStatus.BAD_DATA)
                return
            }
        val board = displayBoardRepository.findById(boardId)
            ?: run {
                session.close(CloseStatus.NOT_ACCEPTABLE)
                return
            }
        sessionsByBoard.computeIfAbsent(boardId) { ConcurrentHashMap.newKeySet() }.add(session)
        val stateJson = boardStateService.fullStateForBoard(board)
        session.sendMessage(TextMessage(stateJson))
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val boardId = extractBoardId(session) ?: return
        sessionsByBoard[boardId]?.remove(session)
    }

    fun broadcast(boardId: UUID, payloadJson: String) {
        sessionsByBoard[boardId]?.forEach { session ->
            if (session.isOpen) {
                session.sendMessage(TextMessage(payloadJson))
            }
        }
    }

    private fun extractBoardId(session: WebSocketSession): UUID? {
        val path = session.uri?.path ?: return null
        val boardId = path.substringAfterLast('/')
        return runCatching { UUID.fromString(boardId) }.getOrNull()
    }
}
