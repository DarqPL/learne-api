package com.learne.backend.teacher.content.audio.dto;

import com.learne.backend.teacher.content.dto.LessonResponse;
import java.util.List;

public record LessonAudioMutation(LessonResponse response, Long courseId,
                                  List<LessonAudioState> cleanupCandidates) {
    public LessonAudioMutation {
        cleanupCandidates = List.copyOf(cleanupCandidates);
    }
}
