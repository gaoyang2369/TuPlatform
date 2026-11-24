package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class AvatarController {
    private final AvatarService avatars;
    AvatarController(AvatarService avatars) { this.avatars = avatars; }

    @PostMapping(value = "/upload/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> upload(HttpServletRequest req, @RequestParam(value = "file", required = false) MultipartFile file) {
        return Map.of("avatar_url", avatars.upload(AuthController.session(req), file));
    }

    @GetMapping(value = "/avatars/{id}.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<byte[]> image(HttpServletRequest req, @PathVariable UUID id) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.read(AuthController.session(req), id));
    }
    @GetMapping(value = "/public/teachers/{id}/avatar", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<byte[]> sharedTeacherAvatar(@PathVariable UUID id) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.sharedTeacherAvatar(id));
    }
}
