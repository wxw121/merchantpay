package dev.merchantpay.platform.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  @Bean
  JsonMapperBuilderCustomizer strictTextContract() {
    return builder ->
        builder.withCoercionConfig(
            LogicalType.Textual,
            config ->
                config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
  }

  @Override
  public void configureApiVersioning(ApiVersionConfigurer versions) {
    versions.useRequestHeader("X-Api-Version").setDefaultVersion("1.0").addSupportedVersions("1.0");
  }
}
