package io.github.ismoyuan.opspilot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 测试用的最小 SSE 客户端：按行读取 text/event-stream，空行结束一个事件（id、event、data 字段）。非 2xx 响应保留状态码与正文。
 */
final class SseClient implements AutoCloseable {

    /** @param id 没有 id 字段时为空 */
    record Event(String id, String name, String data) {}

    private final HttpResponse<Stream<String>> response;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final StringBuilder body = new StringBuilder();
    private final Thread reader;

    private SseClient(HttpResponse<Stream<String>> response) {
        this.response = response;
        this.reader = Thread.ofVirtual().start(this::read);
    }

    /**
     * @param lastEventId 为空时不发送 Last-Event-ID
     */
    static SseClient open(HttpClient http, URI uri, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return new SseClient(http.send(request.build(), HttpResponse.BodyHandlers.ofLines()));
    }

    int status() {
        return response.statusCode();
    }

    String contentType() {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    /** 非事件流响应的完整正文（读取结束后）。 */
    String body() throws InterruptedException {
        reader.join(Duration.ofSeconds(10));
        return body.toString();
    }

    /** 下一个非 heartbeat 事件；超时为空。 */
    Event next(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Event event = events.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (event == null || !"heartbeat".equals(event.name())) {
                return event;
            }
        }
    }

    /** 下一个任意事件（含 heartbeat）；超时为空。 */
    Event nextIncludingHeartbeat(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** 在给定时间内收到的全部非 heartbeat 事件。 */
    List<Event> drain(Duration window) throws InterruptedException {
        List<Event> received = new ArrayList<>();
        long deadline = System.nanoTime() + window.toNanos();
        Event event;
        while ((event = next(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))) != null) {
            received.add(event);
        }
        return received;
    }

    private void read() {
        String id = null;
        String name = null;
        StringBuilder data = null;
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (response.statusCode() / 100 != 2) {
                    body.append(line);
                    continue;
                }
                if (line.isEmpty()) {
                    if (name != null || data != null) {
                        events.add(new Event(id, name, data == null ? null : data.toString()));
                    }
                    id = null;
                    name = null;
                    data = null;
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    data = data == null
                            ? new StringBuilder(value)
                            : data.append('\n').append(value);
                }
            }
        } catch (RuntimeException closed) {
            // 关闭连接时读取结束
        }
    }

    @Override
    public void close() {
        reader.interrupt();
        response.body().close();
    }
}
