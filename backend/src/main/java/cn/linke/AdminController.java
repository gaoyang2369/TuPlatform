package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/admin", "/api/v1/admin"})
class AdminController {
    private final RequestPublicationService demands;
    private final ModerationService moderation;

    AdminController(RequestPublicationService demands, ModerationService moderation) {
        this.demands = demands;
        this.moderation = moderation;
    }

    @GetMapping("/demands")
    Map<String,Object> demands(HttpServletRequest req, @RequestParam(defaultValue="pending") String status,
                               @RequestParam(required=false) String cursor) {
        String state = switch (status) {
            case "pending" -> "PENDING_REVIEW";
            case "approved" -> "APPROVED";
            case "rejected" -> "REJECTED";
            case "expired" -> "EXPIRED";
            default -> throw ApiError.invalid("status", "请选择有效的审核状态");
        };
        return demands.reviewList(AuthController.session(req), state, cursor);
    }

    @GetMapping("/demands/{id}")
    ReviewSubmission demand(HttpServletRequest req, @PathVariable UUID id) {
        return demands.reviewDetail(AuthController.session(req), id);
    }

    @PostMapping("/demands/{id}/review")
    RequestAction review(HttpServletRequest req, @PathVariable UUID id, @RequestBody ReviewRequest body,
                         @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return demands.decide(AuthController.session(req), id, body, key);
    }

    @GetMapping("/users")
    List<Map<String,Object>> users(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String role,
                                   @RequestParam(defaultValue="0") int offset) {
        return moderation.users(q, role, offset);
    }

    @GetMapping("/users/{id}/reviews")
    List<Map<String,Object>> reviews(@PathVariable UUID id, @RequestParam(defaultValue="0") int offset) {
        return moderation.reviews(id, offset);
    }

    record Reason(String reason) {}
    @PostMapping("/users/{id}/{action:freeze|unfreeze|reset-avatar}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void moderate(HttpServletRequest req, @PathVariable UUID id, @PathVariable String action, @RequestBody Reason body) {
        moderation.userAction(AuthController.session(req), id, action, body.reason());
    }

    @PostMapping("/reviews/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hide(HttpServletRequest req, @PathVariable UUID id, @RequestBody Reason body) {
        moderation.hide(AuthController.session(req), id, body.reason());
    }
}
