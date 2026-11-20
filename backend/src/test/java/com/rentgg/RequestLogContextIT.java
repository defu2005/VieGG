package com.rentgg;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rentgg.order.application.OrderView;
import com.rentgg.shared.web.RequestLogContext;
import com.rentgg.support.IntegrationTest;
import com.rentgg.support.IntegrationTest.Setup;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** userId and orderId are in the MDC while the controller runs, and are gone once the request ends. */
class RequestLogContextIT extends IntegrationTest {

    @Test
    void userAndOrderIdAreSetForTheRequestAndNeverStale() throws Exception {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = order(renter, s, Duration.ofHours(1), 180);

        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogContext.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(events);
        try {
            mvc.perform(get("/api/v1/orders/" + o.id()).header("Authorization", bearer(renter)))
                    .andExpect(status().isOk());
            ILoggingEvent authed = events.list.get(events.list.size() - 1);
            assertThat(authed.getMDCPropertyMap())
                    .containsEntry(RequestLogContext.USER_ID, renter.toString())
                    .containsEntry(RequestLogContext.ORDER_ID, o.id().toString());

            events.list.clear();
            mvc.perform(get("/api/v1/games")).andExpect(status().isOk());
            ILoggingEvent anonymous = events.list.get(events.list.size() - 1);
            assertThat(anonymous.getMDCPropertyMap()).doesNotContainKeys(RequestLogContext.USER_ID, RequestLogContext.ORDER_ID);
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previous);
        }
        assertThat(MDC.get(RequestLogContext.USER_ID)).isNull();
        assertThat(MDC.get(RequestLogContext.ORDER_ID)).isNull();
    }
}
