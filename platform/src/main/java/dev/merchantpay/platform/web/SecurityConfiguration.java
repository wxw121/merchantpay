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
      dev.merchantpay.platform.control.RedisAdmission redisAdmission,
      ConsoleSecurity consoleSecurity,
      org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients,
      @Value("${merchantpay.api-max-inflight:16}") int maximumInflight,
      @Value("${merchantpay.api-admission-write-bias:false}") boolean writeBias,
      @Value("${merchantpay.api-nonce-coalesced:false}") boolean coalescedNonce,
      @Value("${merchantpay.api-admission-borrow:false}") boolean borrowReservations,
      @Value("${merchantpay.console-url:http://127.0.0.1:5173}") String consoleUrl)
      throws Exception {
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(
                    "/api/channel/callback",
                    "/api/channel/alipay/callback",
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
                    request -> {
                      var path = ApiPaths.path(request);
                      return ApiPaths.MERCHANT.matches(path) || ApiPaths.callback(path);
                    }));
    var resolver =
        new org.springframework.security.oauth2.client.web
            .DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver stepUp =
        new org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver() {
          private org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
              customize(
                  jakarta.servlet.http.HttpServletRequest request,
                  org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
                      value) {
            if (value == null || !"1".equals(request.getParameter("step_up"))) return value;
            return org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
                .from(value)
                .additionalParameters(
                    parameters -> {
                      parameters.put("acr_values", "2");
                      parameters.put("max_age", "0");
                      parameters.put("prompt", "login");
                    })
                .build();
          }

          public org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
              resolve(jakarta.servlet.http.HttpServletRequest request) {
            return customize(request, resolver.resolve(request));
          }

          public org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
              resolve(jakarta.servlet.http.HttpServletRequest request, String id) {
            return customize(request, resolver.resolve(request, id));
          }
        };
    http.oauth2Login(
        login ->
            login
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(stepUp))
                .defaultSuccessUrl(consoleUrl + "/", true));
    http.addFilterAfter(
        new HighRiskFilter(consoleSecurity, json),
        org.springframework.security.web.access.intercept.AuthorizationFilter.class);
    http.exceptionHandling(
        errors ->
            errors.defaultAuthenticationEntryPointFor(
                (request, response, failure) -> {
                  response.setStatus(401);
                  response.setContentType("application/json;charset=UTF-8");
                  response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请登录\"}");
                },
                request -> ApiPaths.API.matches(ApiPaths.path(request))));
    http.addFilterBefore(
        new SignatureFilter(
                merchants,
                jdbc,
                clock,
                json,
                rateLimiter,
                maximumInflight,
                availability,
                writeBias,
                coalescedNonce,
                borrowReservations)
            .withRedisAdmission(redisAdmission),
        UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(
        new org.springframework.web.filter.OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              jakarta.servlet.http.HttpServletRequest request,
              jakarta.servlet.http.HttpServletResponse response,
              jakarta.servlet.FilterChain chain)
              throws java.io.IOException, jakarta.servlet.ServletException {
            var path = ApiPaths.path(request);
            if (!ApiPaths.API.matches(path) || ApiPaths.callback(path)) {
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
