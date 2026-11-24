package cn.linke;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class AdminPageController {
    @GetMapping({"/admin", "/admin/", "/terms", "/privacy"})
    String page() { return "forward:/index.html"; }
}
