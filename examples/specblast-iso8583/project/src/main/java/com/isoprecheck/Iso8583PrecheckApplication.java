package com.isoprecheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ISO 8583 授權前檢查服務啟動類別
 */
@SpringBootApplication
public class Iso8583PrecheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(Iso8583PrecheckApplication.class, args);
    }
}