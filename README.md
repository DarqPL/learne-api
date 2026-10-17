# LearnE Backend

## Tài liệu API

- [Audio bài học cho giảng viên: discovery, upload/thay và xóa](docs/teacher-lesson-audio-api.md)

## Kiểm chứng audio trên PostgreSQL

Yêu cầu Java 21 và Docker Engine đang chạy. Chạy tại thư mục `backend` bằng PowerShell; profile này chạy cả Surefire regression và ba suite Failsafe PostgreSQL native:

```powershell
./mvnw.cmd -Paudio-it "-Dit.test=LessonAudioConcurrencyIT,LessonAudioWriterProtocolIT,LessonAudioImportConcurrencyIT" verify
```

Profile `audio-it` dùng Testcontainers với `postgres:17-alpine` và Flyway hiện có; Docker không khả dụng là lỗi kiểm chứng, không fallback sang H2. Cloudinary, ClamAV và AI được mock trong các suite này.
