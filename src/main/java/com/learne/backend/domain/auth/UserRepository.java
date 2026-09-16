package com.learne.backend.domain.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndRole(Long id, UserRole role);

    long countByRole(UserRole role);

    long countByRoleAndLocked(UserRole role, boolean locked);

    List<User> findByRoleOrderByCreatedAtAsc(UserRole role);

    @Query("select user from User user where user.role = :role order by user.createdAt asc, user.id asc")
    List<User> findUsersByRole(@Param("role") UserRole role);
}
