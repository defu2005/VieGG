package com.rentgg.identity.infrastructure;

import com.rentgg.identity.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    List<Role> findAllByOrderByCode();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    Optional<Role> findForUpdate(@Param("code") String code);

    @Query(value = "select count(*) from user_roles where role = :role", nativeQuery = true)
    long countUsersWithRole(@Param("role") String role);

    /** ACTIVE users that hold the permission through any role (lock-out guard). */
    @Query(value = """
            select count(distinct ur.user_id) from user_roles ur
            join role_permissions rp on rp.role_code = ur.role
            join users u on u.id = ur.user_id
            where rp.permission_code = :permission and u.status = 'ACTIVE'""", nativeQuery = true)
    long countActiveUsersWithPermission(@Param("permission") String permission);
}
