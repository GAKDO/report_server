package com.gakdo.trace.report.record.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gakdo.trace.report.record.application.PhotoStore;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 버킷 trace-captures의 원본 사진. 저장소가 계산한 SHA-256 체크섬이 있으면 그것을 쓰고(업로드 때 MinIO가 내용으로 확인한 값),
 * 없으면 내려받아 직접 계산한다. 사진을 다른 파일로 바꾸면 둘 중 어느 쪽이든 봉인 때 해시와 달라진다.
 */
@Component
public class S3PhotoStore implements PhotoStore {

    private final S3Client s3;
    private final String bucket;

    public S3PhotoStore(S3Client s3, @Value("${app.storage.capture-bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Optional<String> currentSha256(String objectKey) {
        HeadObjectResponse head;
        try {
            head = s3.headObject(r -> r.bucket(bucket).key(objectKey).checksumMode(ChecksumMode.ENABLED));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        String checksum = head.checksumSHA256();
        if (checksum != null && !checksum.contains("-")) { // "-N"은 멀티파트 합성 체크섬이라 원본 해시가 아니다
            return Optional.of(HexFormat.of().formatHex(Base64.getDecoder().decode(checksum)));
        }
        return Optional.of(download(objectKey));
    }

    private String download(String objectKey) {
        try (InputStream in = s3.getObject(r -> r.bucket(bucket).key(objectKey))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
