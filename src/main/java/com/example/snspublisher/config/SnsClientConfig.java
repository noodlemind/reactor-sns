package com.example.snspublisher.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

import java.time.Duration;

@Configuration
public class SnsClientConfig {

    @Bean
    public SnsAsyncClient snsAsyncClient() {
        return SnsAsyncClient.builder()
                .httpClient(nettyHttpClient())
                .build();
    }

    /**
     * Optimized Netty HTTP Client for High Throughput SNS Publishing.
     * Default Netty client has a max concurrency of 50, which is too low for 256 partitions.
     */
    @Bean
    public SdkAsyncHttpClient nettyHttpClient() {
        return NettyNioAsyncHttpClient.builder()
                // IMPORTANT: Max Concurrency must be >= Partition Count (256)
                // We set it to 500 to allow headroom for retries and other operations.
                .maxConcurrency(500)
                
                // Connection timeouts
                .connectionAcquisitionTimeout(Duration.ofSeconds(10))
                .connectionTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                
                // Keep-Alive to reuse connections efficiently
                .tcpKeepAlive(true)
                .build();
    }
}
