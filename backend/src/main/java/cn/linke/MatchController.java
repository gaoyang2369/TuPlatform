package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class MatchController {
    private final MatchService matches;
    MatchController(MatchService matches) { this.matches=matches; }
    @GetMapping("/me/matches") Map<String,Object> list(HttpServletRequest req,
        @RequestParam(required=false) String cursor, @RequestParam(required=false) String state,
        @RequestParam(required=false) UUID requestId) { return matches.list(AuthController.session(req),cursor,state,requestId); }
    @GetMapping("/matches/{id}") MatchView detail(@PathVariable UUID id,HttpServletRequest req) { return matches.detail(AuthController.session(req),id); }
    @PostMapping("/matches") MatchStartResult start(@RequestBody StartMatch body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) {
        return matches.start(AuthController.session(req),body,key);
    }
    @PostMapping("/matches/{id}/accept") MatchView accept(@PathVariable UUID id,@RequestBody RespondMatch body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return matches.respond(AuthController.session(req),id,body,key,"ACCEPT"); }
    @PostMapping("/matches/{id}/reject") MatchView reject(@PathVariable UUID id,@RequestBody RespondMatch body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return matches.respond(AuthController.session(req),id,body,key,"REJECT"); }
    @PostMapping("/matches/{id}/withdraw-intent") MatchView withdraw(@PathVariable UUID id,@RequestBody RespondMatch body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return matches.respond(AuthController.session(req),id,body,key,"WITHDRAW"); }
}
