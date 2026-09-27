package com.cs203.healthwatch.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    // temporary — delete once real /events endpoints exist from other tickets
    @GetMapping("/events/ping")
    public String ping() {
        return "pong, you're authenticated as admin";
    }
}