package com.ledgerlens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** Async is on for one thing: indexing a batch for search after its reconcile has committed. */
@SpringBootApplication
@EnableAsync
public class LedgerlensApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerlensApplication.class, args);
    }
}
