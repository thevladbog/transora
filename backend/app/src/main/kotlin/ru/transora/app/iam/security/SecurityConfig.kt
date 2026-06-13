package ru.transora.app.iam.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableAspectJAutoProxy
@EnableConfigurationProperties(IamProperties::class)
class SecurityConfig(
    private val serviceTokenAuthenticationFilter: ServiceTokenAuthenticationFilter,
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/auth/login", "/auth/refresh", "/auth/jwks.json").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/docs/**", "/docs", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/board/**").permitAll()
                    .requestMatchers("/ws/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/hw/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint(
                    org.springframework.security.web.authentication.HttpStatusEntryPoint(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                    ),
                )
            }
            .addFilterBefore(serviceTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
}
