package com.rentgg.settings.api;

import com.rentgg.settings.application.SettingsService;
import com.rentgg.settings.application.SettingsService.SettingView;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    /** Public settings (banner, site texts, withdrawal limits) for every client, logged in or not. */
    @GetMapping("/settings/public")
    public ResponseEntity<Map<String, String>> publicSettings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(settings.publicValues());
    }

    @GetMapping("/admin/settings")
    @PreAuthorize("hasAuthority('setting:read')")
    public List<SettingView> list() {
        return settings.list();
    }

    /** {@code values}: key → new value; {@code null} resets a key to its default. */
    @PutMapping("/admin/settings")
    @PreAuthorize("hasAuthority('setting:update')")
    public List<SettingView> update(@Valid @RequestBody UpdateRequest req) {
        return settings.update(CurrentUser.id(), req.values());
    }

    public record UpdateRequest(@NotNull @Size(min = 1, max = 100) Map<String, String> values) {}
}
