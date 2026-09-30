package io.github.ismoyuan.opspilot.web.recovery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * verify-recovery 请求体（05 §34）。
 *
 * @param resourceKey 用户在外部处理的资源（与 managed_resource.resource_key 同长度上限）
 * @param note 可为空；去除首尾空白后按码点计的上限由应用层校验
 */
public record VerifyRecoveryRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 64) String resourceKey,
        @Size(max = 2000) String note) {}
