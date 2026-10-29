package com.rentgg.notification.api;

import com.rentgg.notification.application.NotificationService;
import com.rentgg.notification.domain.Notification;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.web.PageResponse;
import com.rentgg.shared.web.Paging;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationDto> list(@RequestParam(required = false) Boolean unread,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(service.list(CurrentUser.id(), Boolean.TRUE.equals(unread), pageable), NotificationDto::of);
    }

    @PostMapping("/{id}/read")
    public NotificationDto markRead(@PathVariable UUID id) {
        return NotificationDto.of(service.markRead(CurrentUser.id(), id));
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead(CurrentUser.id()));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", service.unreadCount(CurrentUser.id()));
    }

    public record NotificationDto(UUID id, String type, String title, String body, Map<String, Object> data,
                                  Instant readAt, Instant createdAt) {
        static NotificationDto of(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getData(),
                    n.getReadAt(), n.getCreatedAt());
        }
    }
}
