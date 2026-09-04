package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.student.learning.entity.CourseRecommendationItem;
import com.learne.backend.student.learning.entity.CourseRecommendationPath;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptAnswer;
import com.learne.backend.student.learning.entity.PlacementAttemptQuestion;
import com.learne.backend.student.learning.entity.PlacementPreference;
import com.learne.backend.student.learning.enums.LearningPathSource;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import com.learne.backend.student.learning.repository.CourseRecommendationItemRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationPathRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptAnswerRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptQuestionRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptRepository;
import com.learne.backend.student.learning.repository.PlacementPreferenceRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionOptionRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.content.repository.TopicRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class StudentPlacementServiceTests {
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository questionOptionRepository;
    @Autowired private PlacementAttemptRepository placementAttemptRepository;
    @Autowired private PlacementAttemptQuestionRepository placementAttemptQuestionRepository;
    @Autowired private PlacementAttemptAnswerRepository placementAttemptAnswerRepository;
    @Autowired private PlacementPreferenceRepository placementPreferenceRepository;
    @Autowired private CourseRecommendationPathRepository courseRecommendationPathRepository;
    @Autowired private CourseRecommendationItemRepository courseRecommendationItemRepository;

    @Test
    void placementRepositoriesPersistAttemptsQuestionsAnswersAndPreferences() {
        User student = userRepository.save(user("student", UserRole.STUDENT));
        User teacher = userRepository.save(user("teacher", UserRole.TEACHER));
        Course course = courseRepository.save(course(teacher));
        Topic topic = topicRepository.save(topic(course));
        Lesson lesson = lessonRepository.save(lesson(topic));
        Question question = questionRepository.save(question(lesson, teacher));
        QuestionOption selectedOption = questionOptionRepository.save(option(question, true));

        PlacementAttempt olderAttempt = placementAttemptRepository.save(placementAttempt(student,
                PlacementAttemptStatus.IN_PROGRESS, Instant.parse("2026-01-01T00:00:00Z")));
        PlacementAttempt latestAttempt = placementAttemptRepository.save(placementAttempt(student,
                PlacementAttemptStatus.IN_PROGRESS, Instant.parse("2026-01-02T00:00:00Z")));
        placementAttemptRepository.save(placementAttempt(student,
                PlacementAttemptStatus.SUBMITTED, Instant.parse("2026-01-03T00:00:00Z")));

        Question secondQuestion = questionRepository.save(question(lesson, teacher));
        PlacementAttemptQuestion second = placementAttemptQuestionRepository.save(
                placementAttemptQuestion(latestAttempt, question, 2, "GRAMMAR"));
        PlacementAttemptQuestion first = placementAttemptQuestionRepository.save(
                placementAttemptQuestion(latestAttempt, secondQuestion, 1, "VOCABULARY"));
        PlacementAttemptAnswer answer = placementAttemptAnswerRepository.save(
                placementAttemptAnswer(latestAttempt, question, selectedOption));
        PlacementPreference preference = placementPreferenceRepository.save(placementPreference(student));

        assertThat(placementAttemptRepository.findFirstByUserAndStatusOrderByStartedAtDesc(
                student, PlacementAttemptStatus.IN_PROGRESS)).contains(latestAttempt);
        assertThat(placementAttemptRepository.findByIdAndUser(latestAttempt.getId(), student)).contains(latestAttempt);
        assertThat(placementAttemptRepository.findByUserAndStatus(student, PlacementAttemptStatus.IN_PROGRESS))
                .containsExactlyInAnyOrder(olderAttempt, latestAttempt);
        assertThat(placementAttemptQuestionRepository.findByPlacementAttemptOrderBySequenceOrderAsc(latestAttempt))
                .containsExactly(first, second);
        assertThat(placementAttemptAnswerRepository.findByPlacementAttempt(latestAttempt)).containsExactly(answer);
        assertThat(placementPreferenceRepository.findByUser(student)).contains(preference);
    }

    @Test
    void courseRecommendationRepositoriesPersistPathsAndOrderedItems() {
        User student = userRepository.save(user("student", UserRole.STUDENT));
        User teacher = userRepository.save(user("teacher", UserRole.TEACHER));
        Course grammarCourse = courseRepository.save(course(teacher));
        Course vocabularyCourse = courseRepository.save(course(teacher));

        CourseRecommendationPath archivedPath = courseRecommendationPathRepository.save(
                recommendationPath(student, LearningPathStatus.ARCHIVED, Instant.parse("2026-01-01T00:00:00Z")));
        CourseRecommendationPath olderArchivedPath = courseRecommendationPathRepository.save(
                recommendationPath(student, LearningPathStatus.ARCHIVED, Instant.parse("2026-01-02T00:00:00Z")));
        CourseRecommendationPath latestActivePath = courseRecommendationPathRepository.save(
                recommendationPath(student, LearningPathStatus.ACTIVE, Instant.parse("2026-01-03T00:00:00Z")));
        CourseRecommendationItem second = courseRecommendationItemRepository.save(
                recommendationItem(latestActivePath, vocabularyCourse, 2, "Build vocabulary", new BigDecimal("0.7500")));
        CourseRecommendationItem first = courseRecommendationItemRepository.save(
                recommendationItem(latestActivePath, grammarCourse, 1, "Review grammar", new BigDecimal("0.9500")));

        assertThat(courseRecommendationPathRepository.findByUserAndStatus(student, LearningPathStatus.ACTIVE))
                .containsExactly(latestActivePath);
        assertThat(courseRecommendationPathRepository.findByUserAndStatus(student, LearningPathStatus.ARCHIVED))
                .containsExactlyInAnyOrder(archivedPath, olderArchivedPath);
        assertThat(courseRecommendationPathRepository.findFirstByUserAndStatusOrderByGeneratedAtDesc(
                student, LearningPathStatus.ACTIVE)).contains(latestActivePath);
        assertThat(courseRecommendationItemRepository.findByPathOrderBySequenceOrderAsc(latestActivePath))
                .containsExactly(first, second);
    }

    private User user(String label, UserRole role) {
        User user = new User();
        user.setEmail(label + "+" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("password-hash");
        user.setFullName(label + " user");
        user.setRole(role);
        return user;
    }

    private Course course(User teacher) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName("English " + UUID.randomUUID());
        course.setType(CourseType.COMPREHENSIVE);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        return course;
    }

    private Topic topic(Course course) {
        Topic topic = new Topic();
        topic.setCourse(course);
        topic.setName("Basics");
        topic.setOrderIndex(1);
        return topic;
    }

    private Lesson lesson(Topic topic) {
        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(topic.getCourse().getTeacher());
        lesson.setTitle("Placement lesson");
        lesson.setContent("Placement content");
        lesson.setSkillType(SkillType.GRAMMAR);
        lesson.setOrderIndex(1);
        lesson.setXpReward(10);
        lesson.setPlacementTest(true);
        lesson.setStatus(ContentStatus.APPROVED);
        return lesson;
    }

    private Question question(Lesson lesson, User teacher) {
        Question question = new Question();
        question.setLesson(lesson);
        question.setTeacher(teacher);
        question.setType(QuestionType.MULTIPLE_CHOICE);
        question.setQuestionText("Choose the correct answer");
        question.setCorrectAnswer("A");
        question.setOrderIndex(1);
        question.setDifficulty("EASY");
        question.setXpReward(5);
        question.setStatus(ContentStatus.APPROVED);
        return question;
    }

    private QuestionOption option(Question question, boolean correct) {
        QuestionOption option = new QuestionOption();
        option.setQuestion(question);
        option.setOptionText(correct ? "A" : "B");
        option.setCorrect(correct);
        option.setOrderIndex(1);
        question.getOptions().add(option);
        return option;
    }

    private PlacementAttempt placementAttempt(User user, PlacementAttemptStatus status, Instant startedAt) {
        PlacementAttempt attempt = new PlacementAttempt();
        attempt.setUser(user);
        attempt.setStatus(status);
        attempt.setStartedAt(startedAt);
        attempt.setExpiresAt(startedAt.plusSeconds(1800));
        attempt.setScorePercent(80);
        return attempt;
    }

    private PlacementAttemptQuestion placementAttemptQuestion(PlacementAttempt attempt, Question question,
            int sequenceOrder, String skill) {
        PlacementAttemptQuestion attemptQuestion = new PlacementAttemptQuestion();
        attemptQuestion.setPlacementAttempt(attempt);
        attemptQuestion.setQuestion(question);
        attemptQuestion.setSequenceOrder(sequenceOrder);
        attemptQuestion.setSkill(skill);
        return attemptQuestion;
    }

    private PlacementAttemptAnswer placementAttemptAnswer(PlacementAttempt attempt, Question question,
            QuestionOption selectedOption) {
        PlacementAttemptAnswer answer = new PlacementAttemptAnswer();
        answer.setPlacementAttempt(attempt);
        answer.setQuestion(question);
        answer.setAnswerText("A");
        answer.setSelectedOption(selectedOption);
        answer.setSelectedOptionIdsJson("[" + selectedOption.getId() + "]");
        answer.setCorrect(true);
        answer.setScorePercent(100);
        answer.setFeedback("Correct");
        return answer;
    }

    private PlacementPreference placementPreference(User user) {
        PlacementPreference preference = new PlacementPreference();
        preference.setUser(user);
        preference.setSkippedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return preference;
    }

    private CourseRecommendationPath recommendationPath(User user, LearningPathStatus status, Instant generatedAt) {
        CourseRecommendationPath path = new CourseRecommendationPath();
        path.setUser(user);
        path.setStatus(status);
        path.setWeaknessesJson("[\"grammar\"]");
        path.setSource(LearningPathSource.AI_SERVICE);
        path.setGeneratedAt(generatedAt);
        return path;
    }

    private CourseRecommendationItem recommendationItem(CourseRecommendationPath path, Course course, int sequenceOrder,
            String reason, BigDecimal score) {
        CourseRecommendationItem item = new CourseRecommendationItem();
        item.setPath(path);
        item.setCourse(course);
        item.setSequenceOrder(sequenceOrder);
        item.setReason(reason);
        item.setScore(score);
        return item;
    }
}
