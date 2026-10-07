package com.learne.backend.student.profile.service;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.profile.enums.StudentPreferredSkill;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudentPreferredSkillJsonService {
    private static final TypeReference<List<StudentPreferredSkill>> SKILL_LIST = new TypeReference<>() {};
    private final ObjectMapper objectMapper;

    public StudentPreferredSkillJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(List<StudentPreferredSkill> skills) {
        if (skills == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(skills);
        } catch (JacksonException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Student profile data is invalid.");
        }
    }

    public List<StudentPreferredSkill> fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SKILL_LIST);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Student profile data is invalid.");
        }
    }
}
