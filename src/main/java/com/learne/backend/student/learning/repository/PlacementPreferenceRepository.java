package com.learne.backend.student.learning.repository;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.entity.PlacementPreference;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlacementPreferenceRepository extends JpaRepository<PlacementPreference, Long> {
    Optional<PlacementPreference> findByUser(User user);
}
