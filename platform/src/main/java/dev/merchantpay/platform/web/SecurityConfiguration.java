package dev.merchantpay.platform.web;

import dev.merchantpay.platform.merchant.MerchantService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfiguration {
  @Bean
  @Order(1)
  SecurityFilterChain metrics(
      HttpSecurity http, @Value("${merchantpay.metrics-password}") String password)
      throws Exception {
    if (password.isBlank()) {
      throw new IllegalArgumentException("A separate metrics password is required");
    }
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    var provider =
        new DaoAuthenticationProvider(
            new InMemoryUserDetailsManager(
                User.withUsername("metrics")
                    .password(encoder.encode(password))
                    .roles("MONITOR")
                    .build()));
    provider.setPasswordEncoder(encoder);
    http.securityMatcher("/actuator/**")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("MONITOR"))
        .authenticationProvider(provider)
        .httpBasic(Customizer.withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain security(
      HttpSecurity http,
      MerchantService merchants,
      JdbcTemplate jdbc,
      Clock clock,
      JsonMapper json,
      Access access,
      ApiRateLimiter rateLimiter,
      ApiAvailability availability,
      @Value("${merchantpay.api-max-inflight:16}") int maximumInflight,
      @Value("${merchantpay.console-url:http://127.0.0.1:5173}") String consoleUrl)
      throws Exception {
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(
                    "/api/channel/callback",
                    "/actuator/health/**",
                    "/",
                    "/index.html",
                    "/assets/**",
                    "/favicon.ico",
                    "/oauth2/**",
                    "/login/**",
                    "/error")
                .permitAll()
                .requestMatchers("/actuator/**")
                .hasRole("OPS")
                .anyRequest()
                .authenticated());
    http.csrf(
        csrf ->
            csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    request ->
                        request.getRequestURI().startsWith("/api/v1/")
                            || request.getRequestURI().equals("/api/channel/callback")));
    http.oauth2Login(login -> login.defaultSuccessUrl(consoleUrl + "/", true));
    http.exceptionHandling(
        errors ->
            errors.defaultAuthenticationEntryPointFor(
                (request, response, failure) -> {
                  response.setStatus(401);
                  response.setContentType("application/json;charset=UTF-8");
                  response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请登录\"}");
                },
                request -> request.getRequestURI().startsWith("/api/")));
    http.addFilterBefore(
        new SignatureFilter(
            merchants, jdbc, clock, json, rateLimiter, maximumInflight, availability),
        UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(
        new org.springframework.web.filter.OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              jakarta.servlet.http.HttpServletRequest request,
              jakarta.servlet.http.HttpServletResponse response,
              jakarta.servlet.FilterChain chain)
              throws java.io.IOException, jakarta.servlet.ServletException {
            if (!request.getRequestURI().startsWith("/api/")
                || request.getRequestURI().equals("/api/channel/callback")) {
              chain.doFilter(request, response);
              return;
            }
            Access.Caller caller;
            try {
              caller = access.caller();
            } catch (dev.merchantpay.platform.common.ApiException anonymous) {
              chain.doFilter(request, response);
              return;
            }
            try {
              ScopedValue.where(Access.CURRENT, caller)
                  .call(
                      () -> {
                        chain.doFilter(request, response);
                        return null;
                      });
            } catch (java.io.IOException | jakarta.servlet.ServletException failure) {
              throw failure;
            } catch (Exception failure) {
              throw new jakarta.servlet.ServletException(failure);
            }
          }
        },
        org.springframework.security.web.access.intercept.AuthorizationFilter.class);
    http.logout(
        logout ->
            logout
                .logoutSuccessUrl(consoleUrl + "/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"));
    return http.build();
  }
}
