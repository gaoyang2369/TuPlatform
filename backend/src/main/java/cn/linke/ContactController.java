package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ContactController {
    private final ContactService contacts;
    private final Analytics analytics;
    ContactController(ContactService contacts, Analytics analytics) { this.contacts=contacts; this.analytics=analytics; }
    @GetMapping("/me/contacts") Contacts get(HttpServletRequest req) { return contacts.get(AuthController.session(req)); }
    @PutMapping("/me/contacts") Map<String,Object> save(@RequestBody SaveContacts body,HttpServletRequest req) {
        return contacts.save(AuthController.session(req),body);
    }
    @GetMapping("/matches/{id}/contact-consent") Map<String,Object> grant(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean reveal,@RequestParam(defaultValue="false") boolean statusOnly,HttpServletRequest req) {
        String session = AuthController.session(req);
        var grant = contacts.grant(session,id);
        if (statusOnly) {
            var status = new LinkedHashMap<>(grant);
            status.remove("otherSharedContacts");
            status.remove("mySharedContacts");
            return status;
        }
        if (reveal && Boolean.TRUE.equals(grant.get("canViewContacts")))
            analytics.authenticated("view_contact", (UUID)req.getAttribute("linke.accountId"), session);
        return grant;
    }
    @PutMapping("/matches/{id}/contact-consent") Map<String,Object> agree(@PathVariable UUID id,@RequestBody AgreeContacts body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) {
        return contacts.agree(AuthController.session(req),id,body,key);
    }
    @DeleteMapping("/matches/{id}/contact-consent") Map<String,Object> withdraw(@PathVariable UUID id,@RequestBody WithdrawContacts body,
        @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest req) {
        return contacts.withdraw(AuthController.session(req),id,body,key);
    }
}
