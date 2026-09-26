package io.github.ismoyuan.opspilot.application.secret;

/**
 * 把 DataSourceConnection 的 credentialRef 解析为凭据（07 §63）；Application/Domain 只认识引用，不接触明文存储。
 *
 * <p>解析结果只交给 Provider 建立连接，不得进入 Capability Request、AI 上下文、日志或持久化（06 §20、07 §99）。
 */
public interface SecretResolver {

    /** @throws SecretNotFoundException 引用格式不受支持，或引用的凭据不存在/为空 */
    SecretValue resolve(String credentialRef);
}
