package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class RequestPublicationController {
    private final RequestPublicationService requests;
    RequestPublicationController(RequestPublicationService requests) { this.requests=requests; }
    @PostMapping("/requests/{id}/submit") RequestAction submit(@PathVariable UUID id,@RequestBody SubmitRequest body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return requests.submit(AuthController.session(req),id,body,key); }
    @PostMapping("/requests/{id}/revisions") RequestAction revise(@PathVariable UUID id,@RequestBody VersionBody body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return requests.revise(AuthController.session(req),id,body,key); }
    @GetMapping("/operations/submissions") Map<String,Object> queue(@RequestParam(required=false) String cursor,@RequestParam(required=false) String status,HttpServletRequest req) { return requests.reviewList(AuthController.session(req),status,cursor); }
    @GetMapping("/operations/submissions/{id}") ReviewSubmission detail(@PathVariable UUID id,HttpServletRequest req) { return requests.reviewDetail(AuthController.session(req),id); }
    @PostMapping("/operations/submissions/{id}/decision") RequestAction decide(@PathVariable UUID id,@RequestBody ReviewRequest body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) { return requests.decide(AuthController.session(req),id,body,key); }
    @GetMapping("/public/requests") Map<String,Object> list(@RequestParam(required=false) String cursor,@RequestParam(required=false) String categoryCode,@RequestParam(required=false) String areaCode,@RequestParam(required=false) String serviceMode,@RequestParam(required=false) String location,@RequestParam(required=false) String districtCode,@RequestParam(required=false) String grade,@RequestParam(required=false) String subject) { return requests.publicList(cursor,categoryCode,areaCode,serviceMode,location,districtCode,grade,subject); }
    @GetMapping("/public/requests/{id}") Map<String,Object> publicDetail(@PathVariable UUID id) { return requests.publicDetail(id); }
}
