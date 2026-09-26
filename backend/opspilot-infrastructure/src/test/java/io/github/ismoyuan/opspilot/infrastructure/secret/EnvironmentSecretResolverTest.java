package io.github.ismoyuan.opspilot.infrastructure.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException.Reason;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** env://KEY 解析、SECRET_NOT_FOUND 语义，以及任何路径都不泄露凭据值。 */
class EnvironmentSecretResolverTest {

    private static final String SECRET = "hunter2-db-password";

    private final EnvironmentSecretResolver resolver = new EnvironmentSecretResolver(
            Map.of("OPSPILOT_SHORTLINK_MYSQL_PASSWORD", SECRET, "OPSPILOT_EMPTY_PASSWORD", "")::get);

    @Test
    void resolvesEnvironmentReference() {
        SecretValue value = resolver.resolve("env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD");

        assertThat(value.reveal()).isEqualTo(SECRET);
        assertThat(value.toString()).doesNotContain(SECRET);
        assertThat(String.valueOf(value)).isEqualTo("SecretValue[***]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"env://OPSPILOT_MISSING_PASSWORD", "env://OPSPILOT_EMPTY_PASSWORD"})
    void missingOrEmptyCredentialIsSecretNotFound(String credentialRef) {
        SecretNotFoundException ex =
                catchThrowableOfType(SecretNotFoundException.class, () -> resolver.resolve(credentialRef));

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECRET_NOT_FOUND);
        assertThat(ex.reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(ex.getMessage()).contains(credentialRef).doesNotContain(SECRET);
        assertThat(ex.details()).isEmpty();
        assertThat(ex.getCause()).isNull();
    }

    /** 包括把明文密码误填进 credential_ref 的情况：格式不合法的引用本身不回显。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "hunter2-db-password",
                "vault://secret/shortlink",
                "ENV://OPSPILOT_SHORTLINK_MYSQL_PASSWORD",
                "env://opspilot_shortlink_mysql_password",
                "env://",
                "env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD\n",
                " env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD"
            })
    void unsupportedReferenceIsSecretNotFoundWithoutEcho(String credentialRef) {
        SecretNotFoundException ex =
                catchThrowableOfType(SecretNotFoundException.class, () -> resolver.resolve(credentialRef));

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECRET_NOT_FOUND);
        assertThat(ex.reason()).isEqualTo(Reason.UNSUPPORTED_REF);
        assertThat(ex.getMessage()).isEqualTo("Unsupported credential reference");
        assertThat(ex.details()).isEmpty();
    }

    @Test
    void defaultConstructorReadsProcessEnvironment() {
        EnvironmentSecretResolver processResolver = new EnvironmentSecretResolver();

        assertThat(processResolver.resolve("env://PATH").reveal()).isEqualTo(System.getenv("PATH"));
        assertThat(catchThrowableOfType(
                                SecretNotFoundException.class,
                                () -> processResolver.resolve("env://OPSPILOT_TASK009_SURELY_UNSET"))
                        .reason())
                .isEqualTo(Reason.NOT_FOUND);
    }
}
