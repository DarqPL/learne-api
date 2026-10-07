package com.learne.backend.student.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class StudentProfileApiTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void migrationCreatesStudentProfilesTable() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(null, null, "student_profiles", "user_id")) {
            assertThat(columns.next()).isTrue();
        }
    }

    @Test
    void migrationCreatesPrivacyColumnsWithSafeDefaults() {
        jdbcTemplate.update("""
                insert into users(email, password_hash, full_name, role)
                values ('student@example.com', 'hash', 'Student User', 'STUDENT')
                """);
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where email = 'student@example.com'", Long.class);

        jdbcTemplate.update("insert into student_profiles(user_id) values (?)", userId);

        Boolean publicProfileEnabled = jdbcTemplate.queryForObject(
                "select public_profile_enabled from student_profiles where user_id = ?", Boolean.class, userId);
        Boolean showEmail = jdbcTemplate.queryForObject(
                "select show_email from student_profiles where user_id = ?", Boolean.class, userId);
        Boolean showPhoneNumber = jdbcTemplate.queryForObject(
                "select show_phone_number from student_profiles where user_id = ?", Boolean.class, userId);
        Boolean showDateOfBirth = jdbcTemplate.queryForObject(
                "select show_date_of_birth from student_profiles where user_id = ?", Boolean.class, userId);

        assertThat(publicProfileEnabled).isFalse();
        assertThat(showEmail).isFalse();
        assertThat(showPhoneNumber).isFalse();
        assertThat(showDateOfBirth).isFalse();
    }
}
