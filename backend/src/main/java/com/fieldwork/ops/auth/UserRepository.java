package com.fieldwork.ops.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByRoleNameAndActiveTrue(RoleName roleName);

    List<User> findByTeamIdAndActiveTrue(UUID teamId);

    List<User> findByActiveTrue();

    /**
     * Active users of a role with team and role fetch-joined, for the
     * dispatcher technician directory — no lazy loads escape the
     * transaction.
     */
    @Query(
            """
            select u from User u
            left join fetch u.team
            left join fetch u.role
            where u.active = true and u.role.name = :roleName
            order by u.fullName asc
            """)
    List<User> findActiveByRoleName(@Param("roleName") RoleName roleName);
}
