package com.gitmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "git")
@Getter
@Setter
public class GitConfig {
    private String token;
    private String repositoryUrl;
    private String localPath;
}
