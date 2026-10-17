package com.learne.backend.teacher.content.audio.controller;

import com.learne.backend.domain.auth.User;
import com.learne.backend.teacher.content.audio.dto.AudioTargetKind;
import com.learne.backend.teacher.content.audio.dto.CourseAudioTargetsResponse;
import com.learne.backend.teacher.content.audio.service.LessonAudioReadService;
import com.learne.backend.teacher.content.audio.service.LessonAudioService;
import com.learne.backend.teacher.content.dto.LessonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/teacher/content")
class TeacherLessonAudioController {
    private final LessonAudioReadService reads;
    private final LessonAudioService audio;

    TeacherLessonAudioController(LessonAudioReadService reads, LessonAudioService audio) {
        this.reads = reads;
        this.audio = audio;
    }

    @GetMapping("/courses/{courseId}/audio-targets")
    CourseAudioTargetsResponse targets(@AuthenticationPrincipal User teacher, @PathVariable Long courseId) {
        return reads.targets(teacher, courseId);
    }

    @PostMapping(value = "/lessons/{id}/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LessonResponse uploadLive(@AuthenticationPrincipal User teacher, @PathVariable Long id,
                              @RequestParam("file") MultipartFile file) {
        return audio.upload(teacher, AudioTargetKind.LIVE, id, file);
    }

    @PostMapping(value = "/lesson-revisions/{id}/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LessonResponse uploadRevision(@AuthenticationPrincipal User teacher, @PathVariable Long id,
                                  @RequestParam("file") MultipartFile file) {
        return audio.upload(teacher, AudioTargetKind.REVISION, id, file);
    }

    @DeleteMapping("/lessons/{id}/audio")
    LessonResponse deleteLive(@AuthenticationPrincipal User teacher, @PathVariable Long id) {
        return audio.delete(teacher, AudioTargetKind.LIVE, id);
    }

    @DeleteMapping("/lesson-revisions/{id}/audio")
    LessonResponse deleteRevision(@AuthenticationPrincipal User teacher, @PathVariable Long id) {
        return audio.delete(teacher, AudioTargetKind.REVISION, id);
    }
}
