package io.github.ismoyuan.opspilot.application.recovery;

import java.util.Objects;

/**
 * 从一次成功样本的结构化结果中按注册投影（{@link RecoveryField}）取出的标量（06 §113）。取不到可靠值时是 {@link Unknown}，
 * 不补 0、不取数组第一项。
 */
public sealed interface ProjectedValue {

    /** 有限数值。 */
    record Number(double value) implements ProjectedValue {

        public Number {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("value must be finite");
            }
        }
    }

    /** 结果 Schema 的枚举名，如 RUNNING。 */
    record Text(String value) implements ProjectedValue {

        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /** @param cause 固定的原因码，如 GROUP_MISSING、FIELD_NULL */
    record Unknown(String cause) implements ProjectedValue {

        public static final String GROUP_MISSING = "GROUP_MISSING";
        public static final String FIELD_NULL = "FIELD_NULL";
        public static final String STATE_UNKNOWN = "STATE_UNKNOWN";
        public static final String NOT_A_NUMBER = "NOT_A_NUMBER";
        public static final String RESULT_MISMATCH = "RESULT_MISMATCH";

        public Unknown {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
