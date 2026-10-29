package com.rentgg.shared.error;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, Object> details, String traceId) {}
