package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The alert rules file (deploy/monitoring/rentgg-alerts.yml) has the fields Prometheus needs, and every metric an
 * expression uses is really exposed on /actuator/prometheus. Run `promtool check rules` on the file too.
 */
@AutoConfigureObservability
class AlertRulesIT extends IntegrationTest {

    private static final Path RULES = Path.of("../deploy/monitoring/rentgg-alerts.yml");
    private static final Pattern METRIC = Pattern.compile("rentgg_[a-z_]+");

    @Test
    @SuppressWarnings("unchecked")
    void rulesHaveRequiredFieldsAndUseOnlyExposedMetrics() throws Exception {
        assertThat(RULES).exists();
        Map<String, Object> doc = new Yaml().load(Files.readString(RULES));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) doc.get("groups");
        assertThat(groups).isNotEmpty();

        String scrape = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Set<String> alerts = new TreeSet<>();
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                assertThat(rule).containsKeys("alert", "expr", "for", "labels", "annotations");
                assertThat((Map<String, Object>) rule.get("labels")).containsKey("severity");
                assertThat((Map<String, Object>) rule.get("annotations")).containsKey("summary");
                alerts.add((String) rule.get("alert"));
                Matcher m = METRIC.matcher((String) rule.get("expr"));
                while (m.find()) {
                    String metric = m.group();
                    assertThat(Pattern.compile("(?m)^" + metric + "[{ ]").matcher(scrape).find())
                            .as("metric %s used by %s is exposed", metric, rule.get("alert")).isTrue();
                }
            }
        }
        assertThat(alerts).containsExactly("RentggOutboxBacklog", "RentggOutboxStuck", "RentggRefundRetryFailing");
    }
}
