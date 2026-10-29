package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Client IP behind a reverse proxy: goes through real Tomcat (MockMvc skips the RemoteIpValve). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersIT extends IntegrationTest {

    @LocalServerPort int port;

    @Test
    void clientIpComesFromTrustedProxyHeader() throws Exception {
        // The test client connects from 127.0.0.1, which is a trusted proxy.
        assertThat(loginIp("203.0.113.7")).isEqualTo("203.0.113.7");
        // A client-supplied value on the left is ignored: the rightmost untrusted hop wins.
        assertThat(loginIp("1.2.3.4, 203.0.113.9")).isEqualTo("203.0.113.9");
        // No header: the direct peer.
        assertThat(loginIp(null)).isEqualTo("127.0.0.1");
    }

    /** Logs in a staff user (which writes STAFF_LOGIN to the audit log) and returns the recorded IP. */
    private String loginIp(String forwardedFor) throws Exception {
        UUID admin = user("ADMIN");
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, admin);
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"password-123456\"}"));
        if (forwardedFor != null) {
            req.header("X-Forwarded-For", forwardedFor);
        }
        HttpResponse<String> res = HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return jdbc.queryForObject(
                "select ip from audit_logs where actor_id = ? and action = 'STAFF_LOGIN' order by created_at desc limit 1",
                String.class, admin);
    }
}
