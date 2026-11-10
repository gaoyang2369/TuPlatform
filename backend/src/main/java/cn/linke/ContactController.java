package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ContactController {
    private final ContactService contacts;
    ContactController(ContactService contacts) { this.contacts=contacts; }
    @GetMapping("/me/contacts") Contacts get(HttpServletRequest req) { return contacts.get(AuthController.session(req)); }
    @PutMapping("/me/contacts") Map<String,Object> save(@RequestBody SaveContacts body,HttpServletRequest req) {
        return contacts.save(AuthController.session(req),body);
    }
    @GetMapping("/matches/{id}/contact-consent") Map<String,Object> grant(@PathVariable UUID id,HttpServletRequest req) {
        return contacts.grant(AuthController.session(req),id);
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
