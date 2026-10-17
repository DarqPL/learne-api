# API audio bài học dành cho giảng viên

## 1. Xác thực và endpoint

Mọi endpoint yêu cầu access token hợp lệ của tài khoản có role `TEACHER`, đồng thời kiểm tra quyền sở hữu course/revision/lesson. Mọi loại `SkillType` đều hỗ trợ audio; mỗi bài học có tối đa **một audio hiện hành**, audio là tùy chọn.

Prefix chung: `/api/teacher/content`.

| Phương thức | Đường dẫn | Chức năng |
| --- | --- | --- |
| GET | `/courses/{courseId}/audio-targets` | Lấy danh sách đích audio của latest editable revision |
| POST | `/lessons/{id}/audio` | Upload lần đầu hoặc thay audio của live `Lesson` |
| DELETE | `/lessons/{id}/audio` | Gỡ audio của live `Lesson` |
| POST | `/lesson-revisions/{id}/audio` | Upload lần đầu hoặc thay audio của `LessonRevision` |
| DELETE | `/lesson-revisions/{id}/audio` | Gỡ audio của `LessonRevision` |

POST dùng `multipart/form-data`, field bắt buộc tên `file`; không nhận URL/public ID do client cung cấp để thay cho file. Không tự đặt header multipart boundary khi dùng `curl.exe -F`.

## 2. Discovery và lựa chọn đúng namespace

Discovery luôn lấy **revision mới nhất và yêu cầu revision đó là `DRAFT` hoặc `REJECTED`**. Không tìm một draft cũ nằm sau revision mới hơn không editable. Quy tắc này áp dụng cho cả course published/unpublished, revision-only lesson và trường hợp audio revision khác live sau reimport.

Ví dụ response `200 OK` (ID và URL minh họa):

```json
{
  "courseId": 10,
  "revisionId": 21,
  "revisionStatus": "DRAFT",
  "lessons": [
    {
      "lessonRevisionId": 31,
      "sourceLessonId": 7,
      "topicRevisionId": 15,
      "topicTitle": "Daily life",
      "title": "Morning routine",
      "skillType": "LISTENING",
      "orderIndex": 1,
      "audioUrl": "https://res.cloudinary.com/demo/video/upload/learne/course-audio/example.mp3",
      "audioPublicId": "learne/course-audio/example"
    }
  ]
}
```

- Dùng `lessonRevisionId` trả về để gọi `/lesson-revisions/{id}/audio`. Không đoán ID từ live ID, title hoặc order; hai bảng có thể trùng numeric ID nhưng là hai entity khác nhau.
- `sourceLessonId` có thể `null`, chỉ cung cấp thông tin liên kết; không phải quyền ghi live hoặc chỉ dẫn chuyển namespace.
- Không đưa `lessonRevisionId` từ discovery vào JSON CRUD cũ hoặc `/lessons/{id}/audio`. JSON CRUD giữ resolver và hợp đồng ID hiện có; tính năng audio không thay schema/cách chọn live/revision tree của course detail, course list hay response CRUD.
- Danh sách sắp theo topic order/ID rồi lesson order/ID; latest editable revision rỗng trả `lessons: []`.
- Course thiếu/không thuộc giảng viên: `404`. Thiếu revision hoặc latest revision không editable: `409`; tạo edit revision qua API hiện có khi trạng thái cho phép, rồi đọc lại discovery.
- Discovery là snapshot đọc nhất quán từ DB, không phải write lease. Khi mutation trả `409`, đọc lại và lựa chọn đích từ trạng thái mới.

## 3. Ví dụ PowerShell

Token được đọc từ biến môi trường `TEACHER_ACCESS_TOKEN` do môi trường đăng nhập cung cấp. Các ID dưới đây là ví dụ; thay bằng ID thực tế từ API. `voice.mp3` là file audio hợp lệ tại thư mục đang chạy lệnh.

```powershell
curl.exe --fail-with-body -H "Authorization: Bearer $env:TEACHER_ACCESS_TOKEN" "http://localhost:8080/api/teacher/content/courses/10/audio-targets"

curl.exe --fail-with-body -H "Authorization: Bearer $env:TEACHER_ACCESS_TOKEN" -F "file=@voice.mp3;type=audio/mpeg" "http://localhost:8080/api/teacher/content/lesson-revisions/31/audio"

curl.exe --fail-with-body -X DELETE -H "Authorization: Bearer $env:TEACHER_ACCESS_TOKEN" "http://localhost:8080/api/teacher/content/lesson-revisions/31/audio"
```

Live equivalents, chỉ dùng khi live target đáp ứng điều kiện ở mục 5 và ID thực sự thuộc namespace `Lesson`:

```powershell
curl.exe --fail-with-body -H "Authorization: Bearer $env:TEACHER_ACCESS_TOKEN" -F "file=@voice.mp3;type=audio/mpeg" "http://localhost:8080/api/teacher/content/lessons/7/audio"

curl.exe --fail-with-body -X DELETE -H "Authorization: Bearer $env:TEACHER_ACCESS_TOKEN" "http://localhost:8080/api/teacher/content/lessons/7/audio"
```

Gọi POST lần nữa với file khác để thay audio. Với WAV/M4A/OGG, truyền đúng MIME trong allowlist ở mục 6.

## 4. Response mutation

POST và DELETE thành công đều trả **`200 OK`**, body là `LessonResponse` hiện có. `id` luôn thuộc **namespace được yêu cầu**, kể cả khi có mirror được cập nhật trong cùng transaction. Ví dụ POST tới revision `31`:

```json
{
  "id": 31,
  "title": "Morning routine",
  "description": "Fixture",
  "content": "I wake up early.",
  "skillType": "LISTENING",
  "orderIndex": 1,
  "xpReward": 10,
  "placementTest": false,
  "audioUrl": "https://res.cloudinary.com/demo/video/upload/learne/course-audio/example.mp3",
  "audioPublicId": "learne/course-audio/example",
  "status": "DRAFT",
  "rejectionReason": null,
  "questions": []
}
```

Revision `REJECTED` trả status `REJECTED`. DELETE trả cùng schema với `audioUrl: null`, `audioPublicId: null`; DELETE lặp lại trên bài học hợp lệ vẫn trả `200` với hai field null. Vẫn kiểm tra quyền và trạng thái khi chưa có audio; idempotency không bỏ qua các điều kiện đó.

## 5. Quyền ghi, revision và bảo toàn audio

- Hai namespace resolve đúng bảng, không fallback. ID thiếu/foreign tại lookup ban đầu trả `404`; target owned nhưng không editable/stale/inconsistent trả `409`.
- **LIVE:** course chưa published, status `DRAFT`/`REJECTED`; latest editable revision phải có đúng một lesson đối ứng qua `sourceLesson`, và cặp URL/public ID của mirror phải bằng live. POST/DELETE cập nhật cả hai trong một transaction. Mismatch/thiếu/ambiguous mirror trả `409`; dùng discovery và revision endpoint để xử lý mismatch. Revision-only lesson khác không cản live audio nếu mirror của chính target hợp lệ.
- **REVISION, course chưa published:** chỉ latest `DRAFT`/`REJECTED` được ghi. Có source live hợp lệ cùng course/giảng viên thì đồng thời mirror vào source; thao tác rõ đích này có thể sửa mismatch. Lesson không có source chỉ đổi revision, không tạo live lesson giả.
- **REVISION, course đã published:** chỉ đổi latest editable revision; live/student audio giữ nguyên đến khi quy trình approve publish revision. Không ghi trực tiếp published live, pending/approved/superseded hoặc revision lịch sử.
- Scan/upload thực hiện ngoài transaction/DB lock. Final writer khóa course trước revision, đọc lại ownership/latest/target/audio/mirror. Nếu đích bị xóa hoặc token không còn khớp về target/revision/audio hay identity/trạng thái audio của **mirror được chọn** trong cửa sổ upload thì `409`; không tự retarget sang entity/revision thay thế. Identity của mirror được chụp cho LIVE và source live được chọn làm mirror của REVISION ở course chưa published. Với REVISION của course đã published, không chọn live mirror (`mirrorId`/`mirrorState` đều null): source link hiện tại, nếu có, vẫn được kiểm tra đúng owner/course nhưng source ID không được snapshot độc lập. Vì vậy, chỉ thay source link sang một source hợp lệ khác không nhất thiết gây `409` nếu requested target/revision/audio vẫn giữ nguyên. Token so sánh trạng thái không phát hiện riêng chuỗi ABA khôi phục đúng trạng thái ban đầu.
- JSON CRUD có rebuild live → revision phải kiểm tra guard **trước mutation** dưới khóa. Surviving lesson ở đúng latest editable revision có audio revision-only hoặc mismatch thì `409`, rollback toàn bộ thay vì làm mất audio. Revision lịch sử không nằm trong guard. Ngoại lệ DELETE chỉ thuộc subtree được yêu cầu qua source link; không miễn cho surviving lesson khác hoặc revision-only lesson không liên kết. Import/reimport là thay draft có chủ đích; metadata audio được giữ phải lấy từ DB mới nhất sau khóa.

## 6. Giới hạn file và kiểm tra an toàn

Giới hạn application: **`20 * 1024 * 1024 = 20.971.520 bytes` (20 MiB)**. Đúng giới hạn được chấp nhận nếu các điều kiện khác hợp lệ; vượt một byte trả `413`. Đọc bytes có giới hạn, không chỉ tin size do multipart báo.

| Dạng file | MIME được chấp nhận | Chữ ký được kiểm tra |
| --- | --- | --- |
| MP3 | `audio/mpeg` | `ID3` hoặc prefix MPEG frame sync |
| WAV | `audio/wav`, `audio/x-wav` | `RIFF` và `WAVE` tại offset 8 |
| M4A | `audio/mp4` | `ftyp` tại offset 4 |
| OGG | `audio/ogg` | `OggS` |

Extension không đủ để xác nhận file. Đây là kiểm tra MIME/chữ ký theo policy hiện có, **không phải decode hay kiểm chứng codec đầy đủ**. ClamAV fail-closed: malware trả `400`; scanner timeout/không khả dụng/kết quả không hợp lệ trả `502`, không upload và không đổi DB. Scanner và storage dùng cùng bytes đã đọc, không đọc lại file tùy biến từ client.

Global servlet multipart vẫn mặc định **100MB** cho file và request dùng chung import/video; audio có giới hạn 20 MiB riêng. Override bằng `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` / `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` có thể từ chối request sớm hơn. Proxy/container từ chối trước MVC có thể trả body riêng, không bảo đảm JSON ứng dụng.

## 7. Bảng lỗi

Với lỗi tới được application handler, body hiện có là `{"message":"..."}` (`ApiErrorResponse`), không chứa stack trace hoặc credentials. Nội dung message có thể thay đổi; client xử lý theo HTTP status.

| HTTP | Điều kiện |
| --- | --- |
| 400 | Thiếu `file`, file rỗng/không đọc được, MIME/chữ ký sai, malware, multipart malformed tới MVC |
| 401 | Anonymous/token không hợp lệ; security body hiện có, không bảo đảm `ApiErrorResponse` |
| 403 | Đã xác thực nhưng không có role TEACHER; security body hiện có, không bảo đảm `ApiErrorResponse` |
| 404 | ID thiếu trong đúng namespace hoặc không thuộc giảng viên |
| 409 | Owned nhưng noneditable/stale/superseded, published live, mirror thiếu/ambiguous/inconsistent; token không còn khớp về target/revision/audio hoặc identity/trạng thái audio của mirror được chọn trong cửa sổ upload (không bảo đảm phát hiện riêng source ID của published REVISION, xem mục 5); discovery không có latest editable revision; rebuild guard từ chối |
| 413 | Vượt 20 MiB hoặc servlet multipart size limit; JSON chỉ bảo đảm khi được application xử lý |
| 415 | POST không có `Content-Type: multipart/form-data` |
| 502 | Scanner lỗi/timeout/kết quả không hợp lệ; Cloudinary upload lỗi hoặc kết quả thiếu URL/public ID |
| 500 | Cloudinary chưa được cấu hình |

Quy tắc multipart/media dành riêng audio không thay error contract upload/import/profile khác. Lỗi DB bất ngờ không được đổi thành lỗi file `400`.

## 8. Cấu hình tích hợp

- Cloudinary: `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` (map tới `app.cloudinary.cloud-name`, `api-key`, `api-secret`). Cần credentials hợp lệ khi chạy thật. Audio dùng resource type `VIDEO`, folder `learne/course-audio/`; upload tạo asset mới thay vì ghi đè asset cũ.
- ClamAV: property `clamav.host` mặc định `localhost`, `clamav.port` mặc định `3310`, Spring environment tương ứng `CLAMAV_HOST`, `CLAMAV_PORT`. `TcpClamAvClient` dùng INSTREAM, connect/read timeout 10 giây; cần clamd truy cập được từ backend.
- Các test tự động dùng mock Cloudinary/ClamAV/AI; kết quả test không xác nhận credentials hoặc kết nối provider/scanner thật.

## 9. Commit, cache và vòng đời asset

Response dựng từ DB trong transaction. Sau commit, cache invalidation (teacher dashboard, student catalog/detail/version, admin dashboard) và cleanup là best-effort độc lập: lỗi một action vẫn thử các action khác, ghi log; **DB đã commit vẫn trả `200`** và không cleanup asset mới như rollback.

Đọc DB không cache thấy metadata đã commit. Cache có thể stale nếu invalidation lỗi hoặc reader từ snapshot cũ refill; freshness bị giới hạn theo TTL hiện có, không cam kết mọi read luôn tức thời fresh.

Cleanup kiểm tra cả live và **mọi revision, gồm lịch sử/superseded**, cả reference bằng URL/public ID và trường hợp URL-only/URL alias không chắc chắn. Chỉ thử destroy asset quản lý có provenance đủ chắc chắn và đã hết tham chiếu, sau decision transaction/course lock kết thúc. Metadata lệch, URL dot-segment/alias mơ hồ, cross-course uncertainty hoặc không chứng minh được an toàn thì retain/log. Không dùng URL do client cung cấp để destroy.

Sau lỗi persistence chỉ compensate upload mới khi rollback đã được xác nhận. Commit thành công hoặc **commit outcome UNKNOWN thì giữ asset**; cleanup lỗi không che lỗi gốc. Không có queue/scheduler/durable retry cleanup; không bảo đảm xóa mọi orphan nếu tiến trình chết hoặc provider không trả kết quả.

## 10. Kiểm chứng

Lệnh và điều kiện chạy native PostgreSQL: [README backend](../README.md#kiểm-chứng-audio-trên-postgresql). Test HTTP multipart dùng Tomcat thật với JDK HttpClient; giới hạn servlet thấp chỉ thuộc một test context, không thay cấu hình production.
