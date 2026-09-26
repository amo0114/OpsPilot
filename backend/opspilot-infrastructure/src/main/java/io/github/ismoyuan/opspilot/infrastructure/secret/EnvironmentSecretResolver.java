package io.github.ismoyuan.opspilot.infrastructure.secret;

import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException.Reason;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** V0.1 唯一实现：env://KEY 读取进程环境变量（07 §63）；不支持 Vault 等其他 scheme。 */
@Component
class EnvironmentSecretResolver implements SecretResolver {

    /** 与 V001 ck_data_source_connection_credential_ref 一致。 */
    private static final Pattern ENV_REF = Pattern.compile("env://([A-Z_][A-Z0-9_]*)");

    private final UnaryOperator<String> environment;

    EnvironmentSecretResolver() {
        this(System::getenv);
    }

    EnvironmentSecretResolver(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public SecretValue resolve(String credentialRef) {
        Matcher matcher = credentialRef == null ? null : ENV_REF.matcher(credentialRef);
        if (matcher == null || !matcher.matches()) {
            throw new SecretNotFoundException(Reason.UNSUPPORTED_REF, "Unsupported credential reference");
        }
        String value = environment.apply(matcher.group(1));
        if (value == null || value.isEmpty()) {
            throw new SecretNotFoundException(Reason.NOT_FOUND, "Credential not found: " + credentialRef);
        }
        return new SecretValue(value);
    }
}
