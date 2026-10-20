package com.learne.backend.notification;

import com.learne.backend.domain.auth.User;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping
    NotificationService.Page list(Authentication auth,@RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(required=false) String cursor) {
        return service.list(((User)auth.getPrincipal()).getId(),size,unreadOnly,cursor);
    }
    @GetMapping("/unread-count")
    NotificationService.Count count(Authentication auth) { return service.unreadCount(((User)auth.getPrincipal()).getId()); }
    @PatchMapping("/{id}/read")
    NotificationService.Read read(Authentication auth,@PathVariable UUID id) { return service.read(((User)auth.getPrincipal()).getId(),id); }
}
