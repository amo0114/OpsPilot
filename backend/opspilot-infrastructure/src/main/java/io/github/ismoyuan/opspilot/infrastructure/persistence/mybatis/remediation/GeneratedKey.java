package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

/** MyBatis 回填自增主键的载体。 */
final class GeneratedKey {

    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
