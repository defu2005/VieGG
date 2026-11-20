package com.rentgg.shared.web;

import com.rentgg.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;

/**
 * Puts {@code userId} and {@code orderId} into the MDC for the controller call. It runs after authentication, so the
 * principal is known. Only routes under {@code /api/v1/orders/{id}} count as an order id. Both keys are removed when
 * the request completes, so an anonymous request after an authenticated one carries no stale user.
 */
@Component
public class RequestLogContext implements HandlerInterceptor {

    public static final String USER_ID = "userId";
    public static final String ORDER_ID = "orderId";

    private static final String ORDERS_PREFIX = "/api/v1/orders/";
    private static final Logger log = LoggerFactory.getLogger(RequestLogContext.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.remove(USER_ID);
        MDC.remove(ORDER_ID);
        CurrentUser.optionalId().ifPresent(id -> MDC.put(USER_ID, id.toString()));
        orderId(request).ifPresent(id -> MDC.put(ORDER_ID, id));
        log.debug("request context ready");
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(USER_ID);
        MDC.remove(ORDER_ID);
    }

    private static Optional<String> orderId(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern)
                || !pattern.startsWith(ORDERS_PREFIX)) {
            return Optional.empty();
        }
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars
                && vars.get("id") instanceof String id) {
            return Optional.of(id);
        }
        return Optional.empty();
    }
}
