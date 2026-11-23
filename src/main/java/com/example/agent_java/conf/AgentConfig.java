package com.example.agent_java.conf;

import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EndpointCatalog.class)
public class AgentConfig {

    @Value("${application.agent.api-key}")
    private String apiKey;

    @Bean
    public AnthropicClientAsync agentClient(){
        return AnthropicOkHttpClientAsync.builder().apiKey(apiKey).build();
    }

}
