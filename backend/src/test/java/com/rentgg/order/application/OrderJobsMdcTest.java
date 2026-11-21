package com.rentgg.order.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rentgg.order.domain.Order;
import com.rentgg.order.infrastructure.OrderRepository;
import com.rentgg.shared.web.RequestLogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An order job step logs with the order id in the MDC, so a job's log line can be tied to the order it handled
 * (the same as an HTTP request). Removing the MDC line in {@link OrderJobs#each} makes these tests fail.
 */
class OrderJobsMdcTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);
    private static final Logger STEP_LOG = (Logger) LoggerFactory.getLogger("order.job.step.test");
    private static final Logger JOB_LOG = (Logger) LoggerFactory.getLogger(OrderJobs.class);

    private OrderRepository orders;
    private OrderService service;
    private TransactionTemplate tx;
    private OrderJobs jobs;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        service = mock(OrderService.class);
        OrderExtensionService extensions = mock(OrderExtensionService.class);
        tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        jobs = new OrderJobs(orders, service, extensions, tx, CLOCK);

        events = new ListAppender<>();
        events.start();
        STEP_LOG.addAppender(events);
        JOB_LOG.addAppender(events);
    }

    @AfterEach
    void tearDown() {
        STEP_LOG.detachAppender(events);
        JOB_LOG.detachAppender(events);
    }

    @Test
    void stepLogsCarryTheOrderIdOfTheRecordBeingProcessed() {
        UUID id = UUID.randomUUID();
        when(orders.findDueToStart(any(), anyInt())).thenReturn(List.of(id));
        when(orders.lockForJob(id)).thenReturn(Optional.of(mock(Order.class)));
        when(service.startIfDue(any())).thenAnswer(inv -> {
            STEP_LOG.info("start step ran");
            return true;
        });

        assertThat(jobs.startDueOrders(10)).isEqualTo(1);

        ILoggingEvent step = events.list.stream()
                .filter(e -> e.getFormattedMessage().equals("start step ran")).findFirst().orElseThrow();
        assertThat(step.getMDCPropertyMap()).containsEntry(RequestLogContext.ORDER_ID, id.toString());
    }

    @Test
    void failureLogOfAStepCarriesTheOrderIdToo() {
        UUID id = UUID.randomUUID();
        when(orders.findDueToStart(any(), anyInt())).thenReturn(List.of(id));
        when(orders.lockForJob(id)).thenReturn(Optional.of(mock(Order.class)));
        when(service.startIfDue(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(jobs.startDueOrders(10)).isZero();

        ILoggingEvent failure = events.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Order job step failed")).findFirst().orElseThrow();
        assertThat(failure.getMDCPropertyMap()).containsEntry(RequestLogContext.ORDER_ID, id.toString());
    }

    @Test
    void orderIdIsNotLeftInTheMdcAfterTheStep() {
        UUID id = UUID.randomUUID();
        when(orders.findDueToStart(any(), anyInt())).thenReturn(List.of(id));
        when(orders.lockForJob(id)).thenReturn(Optional.of(mock(Order.class)));
        when(service.startIfDue(any())).thenReturn(true);

        jobs.startDueOrders(10);

        assertThat(org.slf4j.MDC.get(RequestLogContext.ORDER_ID)).isNull();
    }
}
