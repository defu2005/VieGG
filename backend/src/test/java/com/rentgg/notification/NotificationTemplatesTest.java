package com.rentgg.notification;

import com.rentgg.notification.application.NotificationTemplates;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Every event has its own title and its own body; no body is a copy of the title or of another event's body. */
class NotificationTemplatesTest {

    @Test
    void everyEventHasATitleAndADistinctBodyThatIsNotTheTitle() {
        assertThat(NotificationTemplates.events()).hasSize(41);
        Set<String> bodies = new HashSet<>();
        for (String event : NotificationTemplates.events()) {
            NotificationTemplates.Template t = NotificationTemplates.forEvent(event).orElseThrow();
            assertThat(t.title()).as(event + " title").isNotBlank();
            assertThat(t.body()).as(event + " body").isNotBlank().isNotEqualTo(t.title());
            assertThat(t.body().length()).as(event + " body is a sentence, not a label").isGreaterThanOrEqualTo(30);
            assertThat(bodies.add(t.body())).as(event + " body is unique").isTrue();
        }
    }

    @Test
    void unknownEventHasNoTemplate() {
        assertThat(NotificationTemplates.forEvent("NoSuchEvent")).isEmpty();
    }
}
