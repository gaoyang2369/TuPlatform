package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api", "/api/v1"})
class MatchReviewController {
    private final MatchReviewService reviews;

    MatchReviewController(MatchReviewService reviews) { this.reviews = reviews; }

    @GetMapping("/matches/{id}/reviews")
    MatchReviewService.Page list(@PathVariable UUID id, HttpServletRequest req) {
        return reviews.list(AuthController.session(req), id);
    }

    @PostMapping("/matches/{id}/reviews")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void submit(@PathVariable UUID id, @RequestBody MatchReviewService.Submission body,
                @RequestHeader(value="Idempotency-Key", required=false) String key, HttpServletRequest req) {
        reviews.submit(AuthController.session(req), id, body, key);
    }

    @PostMapping("/reviews/{id}/reply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reply(@PathVariable UUID id, @RequestBody MatchReviewService.Reply body,
               @RequestHeader(value="Idempotency-Key", required=false) String key, HttpServletRequest req) {
        reviews.reply(AuthController.session(req), id, body, key);
    }
}
