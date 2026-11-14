package dev.merchantpay.platform.common;

import java.util.Arrays;
import java.util.Set;

/**
 * Authenticated service identity, independent of HTTP so workers and domain modules cannot bypass
 * it.
 */
public record Actor(String id, String merchantId, Set<String> roles) {
  public Actor {
    if (id == null || id.isBlank() || id.length() > 160)
      throw new IllegalArgumentException("Actor required");
    roles = Set.copyOf(roles);
  }

  public void require(String... permitted) {
    if (Arrays.stream(permitted).noneMatch(roles::contains))
      throw new ApiException("FORBIDDEN", 403, "当前角色无此操作权限");
  }

  public String scope(String merchant, boolean required) {
    if (merchantId != null && !merchantId.isBlank()) {
      if (merchant != null && !merchantId.equals(merchant))
        throw new ApiException("NOT_FOUND", 404, "资源不存在");
      return merchantId;
    }
    if (java.util.Collections.disjoint(roles, Set.of("ADMIN", "OPS", "FINANCE", "AUDITOR")))
      throw new ApiException("FORBIDDEN", 403, "账号未关联商户");
    if (required && (merchant == null || merchant.isBlank()))
      throw new ApiException("MERCHANT_REQUIRED", 400, "请选择商户");
    return merchant;
  }

  public void checkMerchant(String actual) {
    scope(actual, true);
  }
}
