package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class Access {
  public record Caller(String actor, String merchantId, Set<String> roles) {}

  public static final ScopedValue<Caller> CURRENT = ScopedValue.newInstance();

  public Caller caller() {
    if (CURRENT.isBound()) {
      return CURRENT.get();
    }
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new ApiException("UNAUTHENTICATED", 401, "请登录");
    }
    if (auth.getPrincipal() instanceof Caller caller) {
      return caller;
    }
    if (auth.getPrincipal() instanceof OidcUser user) {
      var roles = new HashSet<String>();
      var raw = user.getClaimAsStringList("roles");
      if (raw != null) {
        roles.addAll(raw);
      }
      roles.retainAll(Set.of("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT"));
      return new Caller(user.getSubject(), user.getClaimAsString("merchant_id"), Set.copyOf(roles));
    }
    throw new ApiException("UNAUTHENTICATED", 401, "身份无效");
  }

  public void require(String... permitted) {
    var roles = caller().roles();
    if (Arrays.stream(permitted).noneMatch(roles::contains)) {
      throw new ApiException("FORBIDDEN", 403, "当前角色无此操作权限");
    }
  }

  public String merchant(String requested, boolean required) {
    var caller = caller();
    if (caller.roles().isEmpty()) {
      throw new ApiException("FORBIDDEN", 403, "账号尚未授权");
    }
    if (caller.merchantId() != null && !caller.merchantId().isBlank()) {
      if (requested != null && !requested.equals(caller.merchantId())) {
        throw new ApiException("NOT_FOUND", 404, "资源不存在");
      }
      return caller.merchantId();
    }
    if (!Collections.disjoint(caller.roles(), Set.of("ADMIN", "OPS", "FINANCE", "AUDITOR"))) {
      if (required && (requested == null || requested.isBlank())) {
        throw new ApiException("MERCHANT_REQUIRED", 400, "请选择商户");
      }
      return requested;
    }
    throw new ApiException("FORBIDDEN", 403, "账号未关联商户");
  }
}
