package cn.linke;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api","/api/v1"})
class AnalyticsController {
    private final Analytics analytics;
    private final AuthService auth;
    private final RateLimits limits;
    AnalyticsController(Analytics analytics, AuthService auth, RateLimits limits) {
        this.analytics=analytics; this.auth=auth; this.limits=limits;
    }
    record Event(String eventName, UUID sessionId) {}
    @PostMapping("/events") @ResponseStatus(HttpStatus.NO_CONTENT)
    void event(@RequestBody Event event, HttpServletRequest req) {
        if (!("page_view_teachers".equals(event.eventName()) || "open_teacher_detail".equals(event.eventName())) || event.sessionId()==null)
            throw ApiError.invalid("eventName","无效的浏览事件");
        limits.check("events:ip", req.getRemoteAddr(), 1200);
        String token=AuthController.session(req);
        Account actor=auth.sessionAccount(token,false);
        if (actor==null) analytics.record(event.eventName(),null,event.sessionId());
        else analytics.authenticated(event.eventName(),actor.id(),token);
    }

    @GetMapping("/admin/funnel")
    List<Analytics.Step> funnel(@RequestParam(defaultValue="7") int days) {
        return analytics.funnel(days);
    }
}
