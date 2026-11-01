package dev.merchantpay.platform.web;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.shared-sessions-enabled", havingValue = "true")
@EnableJdbcHttpSession(tableName = "console_sessions", maxInactiveIntervalInSeconds = 1800)
public class SharedSessionConfiguration {
  @Bean
  FilterRegistrationBean<Filter> sharedSessionRegistration(SessionRepositoryFilter<?> filter) {
    var registration = new FilterRegistrationBean<Filter>(filter);
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
    return registration;
  }
}
