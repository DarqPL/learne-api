package com.learne.backend.teacher.content.audio.dto;

public record LessonAudioToken(AudioTargetKind kind, Long targetId, Long courseId, Long revisionId,
                               LessonAudioState state, Long mirrorId, LessonAudioState mirrorState) {
}
