package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class RequestDraftController {
    private final RequestDraftService drafts;
    RequestDraftController(RequestDraftService drafts) { this.drafts=drafts; }
    @GetMapping("/publishing-options") Map<String,Object> options() { return drafts.options(); }
    @PostMapping("/requests") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    SavedDraft create(@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return drafts.create(AuthController.session(req),key); }
    @GetMapping("/me/requests") Map<String,Object> list(@RequestParam(required=false) String cursor,HttpServletRequest req) { return drafts.list(AuthController.session(req),cursor); }
    @GetMapping("/requests/{id}") SavedDraft get(@PathVariable UUID id,HttpServletRequest req) { return drafts.get(AuthController.session(req),id); }
    @PatchMapping("/requests/{id}/draft") SavedDraft save(@PathVariable UUID id,@RequestBody DraftPatch patch,HttpServletRequest req) { return drafts.save(AuthController.session(req),id,patch); }
}
