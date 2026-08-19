package com.learne.backend.teacher.profile;

import com.learne.backend.domain.auth.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeacherProfileRepository extends JpaRepository<TeacherProfile, Long> {
    Optional<TeacherProfile> findByUser(User user);

    @Query("""
            select profile from TeacherProfile profile
            join fetch profile.user user
            where user.role = com.learne.backend.domain.auth.UserRole.TEACHER
              and user.active = true
              and user.locked = false
              and profile.status = com.learne.backend.teacher.profile.TeacherProfileStatus.ACTIVE
              and trim(profile.bio) <> ''
              and trim(profile.expertise) <> ''
            order by user.fullName asc, profile.id asc
            """)
    List<TeacherProfile> findPublicProfiles();

    @Query("""
            select profile from TeacherProfile profile
            join fetch profile.user user
            where user.id = :teacherId
              and user.role = com.learne.backend.domain.auth.UserRole.TEACHER
              and user.active = true
              and user.locked = false
              and profile.status = com.learne.backend.teacher.profile.TeacherProfileStatus.ACTIVE
              and trim(profile.bio) <> ''
              and trim(profile.expertise) <> ''
            """)
    Optional<TeacherProfile> findPublicProfileByTeacherId(@Param("teacherId") Long teacherId);
}
