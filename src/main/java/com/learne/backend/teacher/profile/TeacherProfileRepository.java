package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface TeacherProfileRepository extends JpaRepository<TeacherProfile, Long> {
    Optional<TeacherProfile> findByUser(User user);
}
