package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.payment.application.PaymentGateway.ProviderException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Map;

/** Small HTTP/JSON helper shared by the provider gateways (10 s timeouts, errors → ProviderException). */
@Component
public class PspHttp {

    private final RestClient http;
    private final ObjectMapper mapper;

    public PspHttp(ObjectMapper mapper) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(10_000);
        this.http = RestClient.builder().requestFactory(f).build();
        this.mapper = mapper;
    }

    public JsonNode postJson(String url, Object body, Map<String, String> headers) {
        try {
            String raw = http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set)).body(body)
                    .exchange((req, res) -> new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return read(raw);
        } catch (RestClientException e) {
            throw new ProviderException("Không gọi được cổng thanh toán: " + e.getMessage(), e);
        }
    }

    public JsonNode postForm(String url, Map<String, String> form) {
        var body = new LinkedMultiValueMap<String, String>();
        form.forEach(body::add);
        try {
            String raw = http.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
                    .exchange((req, res) -> new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return read(raw);
        } catch (RestClientException e) {
            throw new ProviderException("Không gọi được cổng thanh toán: " + e.getMessage(), e);
        }
    }

    public JsonNode get(String url, Map<String, String> headers) {
        try {
            String raw = http.get().uri(url).headers(h -> headers.forEach(h::set))
                    .exchange((req, res) -> new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return read(raw);
        } catch (RestClientException e) {
            throw new ProviderException("Không gọi được cổng thanh toán: " + e.getMessage(), e);
        }
    }

    public JsonNode read(byte[] raw) {
        try {
            return mapper.readTree(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed JSON");
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ProviderException("Cổng thanh toán trả về rỗng.");
        }
        try {
            return mapper.readTree(raw);
        } catch (IOException e) {
            throw new ProviderException("Cổng thanh toán trả về dữ liệu không đọc được.");
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Ref without dashes: fits the providers' length/charset limits. */
    static String compact(java.util.UUID id) {
        return id.toString().replace("-", "");
    }
}
