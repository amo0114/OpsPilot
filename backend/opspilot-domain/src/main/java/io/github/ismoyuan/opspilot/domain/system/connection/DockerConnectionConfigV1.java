package io.github.ismoyuan.opspilot.domain.system.connection;

/**
 * Docker 连接配置 docker.connection.config / 1：V0.1 经本机 unix socket 访问 Docker Engine，没有可配置项（空对象），也不使用凭据
 * （06 §92～§100）。
 */
public record DockerConnectionConfigV1() {

    public static final String SCHEMA_NAME = "docker.connection.config";
    public static final int SCHEMA_VERSION = 1;
}
