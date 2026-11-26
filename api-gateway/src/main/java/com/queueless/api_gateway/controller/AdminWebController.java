package com.queueless.api_gateway.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the static Admin Verification Dashboard from /admin without needing a separate service.
 */
@Controller
public class AdminWebController {

    @GetMapping({"/admin", "/admin/"})
    public String adminIndex() {
        return "forward:/admin/index.html";
    }
}
