package dev.jasagent.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/run", "/executions/{id}"})
    public String page() {
        return "forward:/index.html";
    }
}
