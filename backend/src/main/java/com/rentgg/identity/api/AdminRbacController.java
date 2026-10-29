package com.rentgg.identity.api;

import com.rentgg.identity.application.RbacService;
import com.rentgg.identity.application.RbacService.PermissionView;
import com.rentgg.identity.application.RbacService.RoleView;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** RBAC administration: roles, their permissions, and which users hold which roles. */
@RestController
@RequestMapping("/api/v1/admin/rbac")
public class AdminRbacController {

    private final RbacService rbac;

    public AdminRbacController(RbacService rbac) {
        this.rbac = rbac;
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    public List<PermissionView> permissions() {
        return rbac.permissions();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public List<RoleView> roles() {
        return rbac.listRoles();
    }

    @GetMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('role:read')")
    public RoleView role(@PathVariable String code) {
        return rbac.getRole(code);
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView create(@Valid @RequestBody CreateRoleRequest req) {
        return rbac.createRole(CurrentUser.id(), req.code(), req.name(), req.description(), req.staff(),
                req.permissions());
    }

    @PutMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('role:manage')")
    public RoleView update(@PathVariable String code, @Valid @RequestBody UpdateRoleRequest req) {
        return rbac.updateRole(CurrentUser.id(), code, req.name(), req.description(), req.staff());
    }

    @PutMapping("/roles/{code}/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    public RoleView setPermissions(@PathVariable String code, @Valid @RequestBody PermissionsRequest req) {
        return rbac.setPermissions(CurrentUser.id(), code, req.permissions());
    }

    @DeleteMapping("/roles/{code}")
    @PreAuthorize("hasAuthority('role:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        rbac.deleteRole(CurrentUser.id(), code);
    }

    @GetMapping("/users/{userId}/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public Set<String> userRoles(@PathVariable UUID userId) {
        return rbac.rolesOf(userId);
    }

    @PostMapping("/users/{userId}/roles")
    @PreAuthorize("hasAuthority('role:assign')")
    public Set<String> grant(@PathVariable UUID userId, @Valid @RequestBody GrantRequest req) {
        return rbac.grantRole(CurrentUser.id(), userId, req.role());
    }

    @DeleteMapping("/users/{userId}/roles/{role}")
    @PreAuthorize("hasAuthority('role:assign')")
    public Set<String> revoke(@PathVariable UUID userId, @PathVariable String role) {
        return rbac.revokeRole(CurrentUser.id(), userId, role);
    }

    public record CreateRoleRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 80) String name,
                                    @Size(max = 500) String description, Boolean staff, Set<String> permissions) {}

    /** {@code staff}: null keeps the current value. */
    public record UpdateRoleRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
                                    Boolean staff) {}

    public record PermissionsRequest(@NotNull Set<String> permissions) {}

    public record GrantRequest(@NotBlank String role) {}
}
