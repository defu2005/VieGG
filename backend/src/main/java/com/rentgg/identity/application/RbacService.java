package com.rentgg.identity.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.identity.domain.Role;
import com.rentgg.identity.domain.User;
import com.rentgg.identity.infrastructure.RoleRepository;
import com.rentgg.identity.infrastructure.UserRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.Permission;
import com.rentgg.shared.security.Roles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runtime management of roles and their permissions (RBAC). Guard rails:
 * <ul>
 *   <li>no privilege escalation: a caller can only add permissions to a role that they hold themselves;</li>
 *   <li>no self-service: a caller cannot change their own roles;</li>
 *   <li>no lock-out: at least one ACTIVE user must keep {@code role:manage} and {@code role:assign};</li>
 *   <li>system roles cannot be deleted, a role still assigned to users cannot be deleted, and every user keeps
 *       the base role USER.</li>
 *   <li>USER is never a staff role: staff accounts stay separate from customer accounts.</li>
 * </ul>
 * Every change is audited and invalidates the permission cache.
 */
@Service
public class RbacService {

    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final List<Permission> LOCKOUT_GUARDED = List.of(Permission.ROLE_MANAGE, Permission.ROLE_ASSIGN);

    private final RoleRepository roles;
    private final UserRepository users;
    private final AuthorityService authorities;
    private final AuditService audit;
    private final Clock clock;

    public RbacService(RoleRepository roles, UserRepository users, AuthorityService authorities, AuditService audit,
                       Clock clock) {
        this.roles = roles;
        this.users = users;
        this.authorities = authorities;
        this.audit = audit;
        this.clock = clock;
    }

    public record PermissionView(String code, String resource, String action, String description) {
        static PermissionView of(Permission p) {
            return new PermissionView(p.code(), p.resource(), p.action(), p.description());
        }
    }

    public record RoleView(String code, String name, String description, boolean system, boolean staff,
                           Set<String> permissions, long userCount) {}

    // ================================================================= queries

    public List<PermissionView> permissions() {
        return Arrays.stream(Permission.values()).map(PermissionView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAllByOrderByCode().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RoleView getRole(String code) {
        return view(find(code));
    }

    @Transactional(readOnly = true)
    public Set<String> rolesOf(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        return new TreeSet<>(u.getRoles());
    }

    // ================================================================= roles

    @Transactional
    public RoleView createRole(UUID actorId, String code, String name, String description, Boolean staff,
                               Set<String> permissions) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (!ROLE_CODE.matcher(normalized).matches()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Mã vai trò chỉ gồm A-Z, 0-9, _ (2–40 ký tự, bắt đầu bằng chữ).");
        }
        if (roles.existsById(normalized)) {
            throw ApiException.conflict("ROLE_EXISTS", "Vai trò " + normalized + " đã tồn tại.");
        }
        Set<String> perms = validPermissions(permissions);
        requireHeld(actorId, perms);
        Role r = new Role(normalized, requireName(name), description, staff == null || staff, perms, clock.instant());
        roles.saveAndFlush(r);
        audit.record(actorId, "ROLE_CREATED", "Role", null, null, snapshot(r), null);
        return view(r);
    }

    @Transactional
    public RoleView updateRole(UUID actorId, String code, String name, String description, Boolean staff) {
        Role r = findForUpdate(code);
        Map<String, Object> before = snapshot(r);
        r.rename(requireName(name), description, clock.instant());
        boolean staffChanged = staff != null && staff != r.isStaff();
        if (staffChanged) {
            if (Roles.USER.equals(r.getCode())) {
                throw ApiException.unprocessable("BASE_ROLE_NOT_STAFF",
                        "Vai trò khách hàng USER không thể là vai trò nhân viên.");
            }
            r.setStaff(staff, clock.instant());
        }
        audit.record(actorId, "ROLE_UPDATED", "Role", null, before, snapshot(r), null);
        if (staffChanged) {
            invalidateAllAfterCommit();
        }
        return view(r);
    }

    /** Replaces the role's permission set. Added permissions must be held by the caller. */
    @Transactional
    public RoleView setPermissions(UUID actorId, String code, Set<String> permissions) {
        Role r = findForUpdate(code);
        Set<String> next = validPermissions(permissions);
        Set<String> added = new HashSet<>(next);
        added.removeAll(r.getPermissions());
        requireHeld(actorId, added);
        Map<String, Object> before = snapshot(r);
        r.replacePermissions(next, clock.instant());
        roles.flush();
        requireNoLockout();
        audit.record(actorId, "ROLE_PERMISSIONS_CHANGED", "Role", null, before, snapshot(r), null);
        invalidateAllAfterCommit();
        return view(r);
    }

    @Transactional
    public void deleteRole(UUID actorId, String code) {
        Role r = findForUpdate(code);
        if (r.isSystem()) {
            throw ApiException.unprocessable("SYSTEM_ROLE", "Không thể xoá vai trò hệ thống " + r.getCode() + ".");
        }
        long holders = roles.countUsersWithRole(r.getCode());
        if (holders > 0) {
            throw ApiException.conflict("ROLE_IN_USE", "Vai trò đang được gán cho " + holders + " người dùng.");
        }
        Map<String, Object> before = snapshot(r);
        roles.delete(r);
        roles.flush();
        audit.record(actorId, "ROLE_DELETED", "Role", null, before, null, null);
        invalidateAllAfterCommit();
    }

    // ================================================================= user ↔ role

    @Transactional
    public Set<String> grantRole(UUID actorId, UUID userId, String roleCode) {
        requireNotSelf(actorId, userId);
        Role r = find(roleCode);
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        if (u.getRoles().contains(r.getCode())) {
            return new TreeSet<>(u.getRoles());
        }
        u.addRole(r.getCode());
        u.touch(clock.instant());
        users.flush();
        audit.record(actorId, "USER_ROLE_GRANTED", "User", userId, null, Map.of("role", r.getCode()), null);
        invalidateAfterCommit(userId);
        return new TreeSet<>(u.getRoles());
    }

    @Transactional
    public Set<String> revokeRole(UUID actorId, UUID userId, String roleCode) {
        requireNotSelf(actorId, userId);
        String code = roleCode == null ? "" : roleCode.trim().toUpperCase();
        if (Roles.USER.equals(code)) {
            throw ApiException.unprocessable("BASE_ROLE_REQUIRED", "Không thể gỡ vai trò cơ bản USER.");
        }
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        if (!u.getRoles().contains(code)) {
            return new TreeSet<>(u.getRoles());
        }
        u.removeRole(code);
        u.touch(clock.instant());
        users.flush();
        requireNoLockout();
        audit.record(actorId, "USER_ROLE_REVOKED", "User", userId, Map.of("role", code), null, null);
        invalidateAfterCommit(userId);
        return new TreeSet<>(u.getRoles());
    }

    // ================================================================= helpers

    private Role find(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        return roles.findById(c).orElseThrow(() -> ApiException.notFound("Vai trò"));
    }

    private Role findForUpdate(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        return roles.findForUpdate(c).orElseThrow(() -> ApiException.notFound("Vai trò"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tên vai trò là bắt buộc (tối đa 80 ký tự).");
        }
        return name.trim();
    }

    private static Set<String> validPermissions(Set<String> codes) {
        if (codes == null) {
            return Set.of();
        }
        Set<String> unknown = new TreeSet<>();
        for (String c : codes) {
            if (Permission.byCode(c).isEmpty()) {
                unknown.add(c);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_PERMISSION", "Quyền không tồn tại: " + String.join(", ", unknown));
        }
        return Set.copyOf(codes);
    }

    /**
     * Anti-escalation: the caller must already hold every permission they hand out. Customer self-service
     * permissions are exempt: they are not privileges, and staff accounts never hold them.
     */
    private void requireHeld(UUID actorId, Set<String> permissions) {
        Set<String> missing = new TreeSet<>(permissions);
        missing.removeIf(c -> Permission.byCode(c).map(Permission::selfService).orElse(false));
        missing.removeAll(authorities.fresh(actorId).permissions());
        if (!missing.isEmpty()) {
            throw ApiException.forbidden("PRIVILEGE_ESCALATION",
                    "Bạn không thể cấp quyền mà chính bạn không có: " + String.join(", ", missing));
        }
    }

    private static void requireNotSelf(UUID actorId, UUID userId) {
        if (actorId.equals(userId)) {
            throw ApiException.forbidden("SELF_ROLE_CHANGE_FORBIDDEN", "Không được tự thay đổi vai trò của chính mình.");
        }
    }

    private void requireNoLockout() {
        for (Permission p : LOCKOUT_GUARDED) {
            if (roles.countActiveUsersWithPermission(p.code()) == 0) {
                throw ApiException.conflict("LAST_ADMIN",
                        "Thao tác này sẽ khiến không còn ai có quyền " + p.code() + ".");
            }
        }
    }

    private RoleView view(Role r) {
        return new RoleView(r.getCode(), r.getName(), r.getDescription(), r.isSystem(), r.isStaff(),
                new TreeSet<>(r.getPermissions()), roles.countUsersWithRole(r.getCode()));
    }

    private static Map<String, Object> snapshot(Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("staff", r.isStaff());
        m.put("permissions", new TreeSet<>(r.getPermissions()));
        return m;
    }

    private void invalidateAfterCommit(UUID userId) {
        authorities.invalidate(userId);
        afterCommit(() -> authorities.invalidate(userId));
    }

    private void invalidateAllAfterCommit() {
        authorities.invalidateAll();
        afterCommit(authorities::invalidateAll);
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        }
    }
}
