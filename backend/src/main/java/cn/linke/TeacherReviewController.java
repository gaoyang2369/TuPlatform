package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/teachers", "/api/v1/teachers"})
class TeacherReviewController {
    private final TeacherReviewService reviews;
    TeacherReviewController(TeacherReviewService reviews) { this.reviews = reviews; }

    @GetMapping("/{id}/reviews")
    Map<String,Object> list(@PathVariable UUID id, @RequestParam(required=false) UUID cursor,
                            @RequestParam(defaultValue="5") int limit, HttpServletRequest req) {
        return reviews.list(AuthController.session(req), id, cursor, limit);
    }
}
