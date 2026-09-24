package com.fieldwork.ops.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByRoleNameAndActiveTrue(RoleName roleName);

    List<User> findByTeamIdAndActiveTrue(UUID teamId);

    List<User> findByActiveTrue();
}
