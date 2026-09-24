package com.fieldwork.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Fieldwork Ops service.
 *
 * The service is organized as a modular monolith: each top-level package
 * (auth, workorder, sla, dispatch, attachment, notification, reporting)
 * is a module with its own controllers, services and repositories.
 * Cross-module communication happens through Spring application events,
 * so the seams are in place if a module ever needs to be split out.
 */
@SpringBootApplication
public class FieldworkOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FieldworkOpsApplication.class, args);
    }
}
