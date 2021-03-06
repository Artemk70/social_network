package com.skillbox.socialnetwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableCaching
@EnableDiscoveryClient
@SpringBootApplication
public class NetworkApplication {
    public static void main(String[] args) {
        SpringApplication.run(NetworkApplication.class, args);
    }

}
