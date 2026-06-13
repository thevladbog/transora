package ru.transora.app.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.transora.app.domain.DomainRuleViolation

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(DomainRuleViolation::class)
    fun domainRuleViolation(error: DomainRuleViolation): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.message ?: "Domain rule violation")

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(error: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.message ?: "Resource not found")

    @ExceptionHandler(AccessDeniedException::class)
    fun accessDenied(error: AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, error.message ?: "Access denied")

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(error: ResponseStatusException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(error.statusCode, error.reason ?: error.message ?: "Request failed")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
}

