package com.rentgg.identity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A named set of permissions. System roles (USER, SUPPORT, ADMIN, FINANCE) cannot be deleted.
 *
 * <p>{@code staff}: a user holding any staff role is a staff account and does not get the permissions of
 * non-staff roles (the customer role USER).
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    private String code;
    private String name;
    private String description;
    @Column(name = "is_system")
    private boolean system;
    @Column(name = "is_staff")
    private boolean staff = true;
    private Instant createdAt;
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "permission_code")
    private Set<String> permissions = new HashSet<>();

    protected Role() {}

    public Role(String code, String name, String description, boolean staff, Set<String> permissions, Instant now) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.system = false;
        this.staff = staff;
        this.permissions.addAll(permissions);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isSystem() { return system; }
    public boolean isStaff() { return staff; }
    public Set<String> getPermissions() { return permissions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void rename(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }

    public void setStaff(boolean staff, Instant now) {
        this.staff = staff;
        this.updatedAt = now;
    }

    public void replacePermissions(Set<String> next, Instant now) {
        permissions.clear();
        permissions.addAll(next);
        this.updatedAt = now;
    }
}
