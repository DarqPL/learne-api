package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=2KB",
        "server.servlet.context-path=/audio-test"})
class LessonAudioMultipartHttpTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean StorageService storage;
    @MockitoBean ClamAvClient scanner;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String token;

    @BeforeEach
    void loginThroughRealSecurityFilters() throws Exception {
        TestDatabaseCleaner.clean(jdbc);
        var teacher = new User();
        teacher.setEmail("multipart@example.com");
        teacher.setFullName("Multipart Teacher");
        teacher.setRole(UserRole.TEACHER);
        teacher.setPasswordHash(passwords.encode("Password123"));
        teacher.setActive(true);
        teacher.setLocked(false);
        teacher.setMustChangePassword(false);
        users.saveAndFlush(teacher);
        var login = send("/api/auth/login", "application/json",
                "{\"email\":\"multipart@example.com\",\"password\":\"Password123\"}", false);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        token = json.readTree(login.body()).get("accessToken").asText();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "lesson-revisions"})
    void servletRequestSizeLimitReturns413Message(String namespace) throws Exception {
        expectError(send(audio(namespace), "multipart/form-data; boundary=AudioBoundary",
                multipart("file", "x".repeat(3072)), true), 413);
        verifyNoInteractions(storage, scanner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "lesson-revisions"})
    void servletFileSizeLimitReturns413Message(String namespace) throws Exception {
        expectError(send(audio(namespace), "multipart/form-data; boundary=AudioBoundary",
                multipart("file", "x".repeat(1100)), true), 413);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "lesson-revisions"})
    void missingFileReturns400Message(String namespace) throws Exception {
        expectError(send(audio(namespace), "multipart/form-data; boundary=AudioBoundary",
                multipart("other", "ID3audio"), true), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "lesson-revisions"})
    void malformedMultipartReturns400Message(String namespace) throws Exception {
        expectError(send(audio(namespace), "multipart/form-data", multipart("file", "ID3audio"), true), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "lesson-revisions"})
    void wrongMediaBeforeHandlerSelectionReturns415Message(String namespace) throws Exception {
        expectError(send(audio(namespace), "application/json", "{}", true), 415);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/teacher/profile/avatar", "/api/teacher/content/import"})
    void unrelatedMediaErrorsKeepFrameworkBody(String uri) throws Exception {
        var response = send(uri, "application/json", "{}", true);
        // The framework sends an error; the existing secured ERROR dispatch returns an empty 401.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/teacher/profile/avatar", "/api/teacher/content/import"})
    void unrelatedMissingFileAndPreHandlerSizeErrorsKeepExistingHttpContract(String uri) throws Exception {
        for (String body : new String[]{multipart("other", "small"), multipart("file", "x".repeat(3072))}) {
            var response = send(uri, "multipart/form-data; boundary=AudioBoundary", body, true);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }
        var unauthenticated = send(uri, "multipart/form-data; boundary=AudioBoundary", multipart("file", "small"), false);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.body()).isEmpty();
    }

    private String audio(String namespace) { return "/api/teacher/content/" + namespace + "/1/audio"; }

    private String multipart(String name, String contents) {
        return "--AudioBoundary\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"audio.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n"
                + contents + "\r\n--AudioBoundary--\r\n";
    }

    private HttpResponse<String> send(String path, String type, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/audio-test" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void expectError(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.size()).as(response.body()).isEqualTo(1);
        assertThat(body.path("message").asText()).isNotBlank();
    }
}
