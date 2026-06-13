package ru.transora.app.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
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

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
}

