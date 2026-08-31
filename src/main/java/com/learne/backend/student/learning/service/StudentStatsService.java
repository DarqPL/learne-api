package com.learne.backend.student.learning.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.student.learning.dto.StudentStatsResponse;
import com.learne.backend.student.learning.entity.LearningActivity;
import com.learne.backend.student.learning.entity.UserStats;
import com.learne.backend.student.learning.enums.LearningActivitySourceType;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional
public class StudentStatsService {
    private final UserStatsRepository userStatsRepository;
    private final LearningActivityRepository learningActivityRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate createStatsTransactionTemplate;

    public StudentStatsService(UserStatsRepository userStatsRepository,
                               LearningActivityRepository learningActivityRepository,
                               EntityManager entityManager,
                               PlatformTransactionManager transactionManager) {
        this.userStatsRepository = userStatsRepository;
        this.learningActivityRepository = learningActivityRepository;
        this.entityManager = entityManager;
        this.createStatsTransactionTemplate = new TransactionTemplate(transactionManager);
        this.createStatsTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UserStats getOrCreateStats(User student) {
        return userStatsRepository.findByUser(student)
                .orElseGet(() -> findOrCreateStats(student));
    }

    public StudentStatsResponse getStats(User student) {
        return StudentStatsResponse.from(getOrCreateStats(student));
    }

    public StudentStatsAwardResult awardLessonPass(User student, Long lessonId, int xp) {
        LocalDate today = LocalDate.now();
        UserStats stats = userStatsRepository.findByUserForUpdate(student)
                .orElseGet(() -> {
                    getOrCreateStats(student);
                    return userStatsRepository.findByUserForUpdate(student).orElseThrow();
                });
        boolean alreadyHasActivityToday = learningActivityRepository.existsByUserAndActivityDate(student, today);
        if (learningActivityRepository.existsByUserAndSourceTypeAndSourceIdAndActivityDate(
                student, LearningActivitySourceType.LESSON_QUIZ_PASS, lessonId, today)) {
            return new StudentStatsAwardResult(false, StudentStatsResponse.from(stats));
        }

        LearningActivity activity = new LearningActivity();
        activity.setUser(student);
        activity.setSourceType(LearningActivitySourceType.LESSON_QUIZ_PASS);
        activity.setSourceId(lessonId);
        activity.setActivityDate(today);
        activity.setXpEarned(xp);
        learningActivityRepository.save(activity);

        stats.setTotalXp(stats.getTotalXp() + xp);
        if (!alreadyHasActivityToday) {
            updateStreak(stats, today);
        }

        return new StudentStatsAwardResult(true, StudentStatsResponse.from(userStatsRepository.save(stats)));
    }

    private UserStats findOrCreateStats(User student) {
        try {
            return createStatsTransactionTemplate.execute(status -> createStats(student));
        } catch (DataIntegrityViolationException ex) {
            entityManager.clear();
            return userStatsRepository.findByUser(student).orElseThrow(() -> ex);
        }
    }

    private UserStats createStats(User student) {
        UserStats stats = new UserStats();
        stats.setUser(student);
        stats.setTotalXp(0);
        stats.setCurrentStreakDays(0);
        stats.setLongestStreakDays(0);
        return userStatsRepository.saveAndFlush(stats);
    }

    private void updateStreak(UserStats stats, LocalDate today) {
        LocalDate lastActivityDate = stats.getLastActivityDate();
        if (lastActivityDate == null) {
            stats.setCurrentStreakDays(1);
        } else if (lastActivityDate.equals(today.minusDays(1))) {
            stats.setCurrentStreakDays(stats.getCurrentStreakDays() + 1);
        } else if (!lastActivityDate.equals(today)) {
            stats.setCurrentStreakDays(1);
        }

        stats.setLongestStreakDays(Math.max(stats.getLongestStreakDays(), stats.getCurrentStreakDays()));
        stats.setLastActivityDate(today);
    }
}
