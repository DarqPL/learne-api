package com.learne.backend.teacher.content.audio.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.LessonRevision;

public record LessonAudioState(String url, String publicId) {
    public static LessonAudioState empty() { return new LessonAudioState(null, null); }
    public static LessonAudioState from(Lesson lesson) {
        return new LessonAudioState(lesson.getAudioUrl(), lesson.getAudioPublicId());
    }
    public static LessonAudioState from(LessonRevision lesson) {
        return new LessonAudioState(lesson.getAudioUrl(), lesson.getAudioPublicId());
    }
}
