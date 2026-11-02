package com.card.config;

import com.card.rules.AuthorizationRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 授權規則引擎 Spring Bean 設定
 * <p>
 * {@link AuthorizationRules} 本身無 Spring 註解（規格要求），
 * 透過此 Configuration 類別將其納入 Spring IoC 容器。
 */
@Configuration
public class AuthorizationRulesConfig {

    /**
     * 建立 AuthorizationRules Bean
     *
     * @return AuthorizationRules 實例
     */
    @Bean
    public AuthorizationRules authorizationRules() {
        return new AuthorizationRules();
    }
}