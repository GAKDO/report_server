package com.gakdo.trace.report.record.infra;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.gakdo.trace.report.record.domain.ChainSigner;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 서명 키·저장소 클라이언트·봉인 스케줄러. */
@Configuration
@EnableScheduling
public class RecordConfig {

    private static final Logger log = LoggerFactory.getLogger(RecordConfig.class);

    @Bean
    ChainSigner chainSigner(@Value("${app.chain.signing-key:}") String key,
            @Value("${app.chain.allow-ephemeral-key:false}") boolean allowEphemeral) {
        Ed25519ChainSigner signer = Ed25519ChainSigner.fromConfig(key, allowEphemeral);
        log.info("해시 체인 서명 공개 키(Ed25519, raw base64): {}", signer.publicKeyBase64());
        return signer;
    }

    /** MinIO는 경로 방식. SDK 기본 CRC 체크섬은 필요할 때만(capture-server와 같음). 키가 비면 익명. */
    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${app.storage.endpoint}") String endpoint, @Value("${app.storage.region}") String region,
            @Value("${app.storage.access-key:}") String accessKey, @Value("${app.storage.secret-key:}") String secretKey) {
        AwsCredentialsProvider credentials = accessKey == null || accessKey.isBlank()
                ? AnonymousCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
