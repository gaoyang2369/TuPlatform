package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ProfileController {
    private final ProfileService profiles;
    ProfileController(ProfileService profiles) { this.profiles = profiles; }
    @GetMapping("/profile-options") Map<String, Object> options() { return profiles.options(); }
    @GetMapping("/me/profile") Profile get(HttpServletRequest req) { return profiles.get(AuthController.session(req)); }
    @PatchMapping("/me/profile") Profile save(@RequestBody BaseProfileBody body, HttpServletRequest req) { return profiles.saveBase(AuthController.session(req), body); }
    @PutMapping("/me/teacher-profile") Profile teacher(@RequestBody TeacherBody body, HttpServletRequest req) { return profiles.saveTeacher(AuthController.session(req), body); }
    @PostMapping("/me/teacher-profile/publish") Profile publish(@RequestBody VersionBody body, @RequestHeader(value="Idempotency-Key", required=false) String key, HttpServletRequest req) {
        return profiles.visibility(AuthController.session(req), body, true, key);
    }
    @PostMapping("/me/teacher-profile/unpublish") Profile unpublish(@RequestBody VersionBody body, @RequestHeader(value="Idempotency-Key", required=false) String key, HttpServletRequest req) {
        return profiles.visibility(AuthController.session(req), body, false, key);
    }
    @GetMapping("/teachers/{id}") Map<String, Object> teacher(@PathVariable UUID id, HttpServletRequest req) { return profiles.publicTeacher(AuthController.session(req), id); }
    @GetMapping("/teachers") Map<String,Object> teachers(HttpServletRequest req,@RequestParam(required=false) String cursor,
        @RequestParam(required=false) String categoryCode,@RequestParam(required=false) String areaCode,@RequestParam(required=false) String serviceMode) {
        return profiles.publicTeachers(AuthController.session(req),cursor,categoryCode,areaCode,serviceMode);
    }
}
