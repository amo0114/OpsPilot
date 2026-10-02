package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.faultlab.FaultInjector;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryInjector;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyInjector;
import io.github.ismoyuan.opspilot.application.faultlab.StatisticsConsumerStopInjector;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * demo profile 装配的真实注入器（08 TASK-093～095）：S1、S2、S3 各一个，只控制 shortlink-platform 的对应目标资源。本测试不调用它们，以免
 * 触及本机 Docker 或 Toxiproxy；默认 profile 没有注入器见 ApplicationWiringTest。
 */
@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class DemoFaultInjectorWiringTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    List<FaultInjector> injectors;

    @Test
    void theDemoProfileWiresTheRealInjectorsForTheirBoundTargets() {
        assertThat(injectors)
                .extracting(FaultInjector::scenarioKey)
                .containsExactlyInAnyOrder("redis-latency", "mysql-slow-query", "statistics-consumer-stop");
        FaultInjector redisLatency = injectors.stream()
                .filter(RedisLatencyInjector.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(redisLatency.controls("shortlink-platform", "shortlink-redis"))
                .isTrue();
        assertThat(redisLatency.controls("shortlink-platform", "statistics-consumer"))
                .isFalse();
        FaultInjector consumerStop = injectors.stream()
                .filter(StatisticsConsumerStopInjector.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(consumerStop.controls("shortlink-platform", "statistics-consumer"))
                .isTrue();
        FaultInjector slowQuery = injectors.stream()
                .filter(MysqlSlowQueryInjector.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(slowQuery.controls("shortlink-platform", "shortlink-mysql")).isTrue();
        assertThat(slowQuery.controls("shortlink-platform", "shortlink-redis")).isFalse();
    }
}
