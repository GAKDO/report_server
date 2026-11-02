package com.gakdo.trace.report.record.application;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.report.record.domain.ChainCheck;
import com.gakdo.trace.report.record.domain.ChainRecord;
import com.gakdo.trace.report.record.domain.ChainSigner;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.PhotoRef;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;

/**
 * 체인을 다시 계산하고 봉인한 사진을 저장소에서 다시 본다. 공개 검증이 저장소를 너무 자주 두드리지 않게 결과를 잠깐(app.verify.cache-ttl) 둔다.
 * 저장소에 닿지 못하면 위변조가 아니므로 체인 계산만으로 답하고 결과를 두지 않는다.
 */
@Component
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);

    private record Cached(ChainCheck.Result result, Instant at) {
    }

    private final CheckpointViewRepository views;
    private final ChainSigner signer;
    private final PhotoStore photos;
    private final ObjectMapper mapper;
    private final boolean checkObjects;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ChainVerifier(CheckpointViewRepository views, ChainSigner signer, PhotoStore photos, ObjectMapper mapper,
            @Value("${app.verify.check-objects:true}") boolean checkObjects,
            @Value("${app.verify.cache-ttl:PT30S}") Duration ttl) {
        this.views = views;
        this.signer = signer;
        this.photos = photos;
        this.mapper = mapper;
        this.checkObjects = checkObjects;
        this.ttl = ttl;
    }

    public ChainCheck.Result verify(String rentalId, List<ChainRecord> records) {
        Cached cached = cache.get(rentalId);
        if (cached != null && cached.at().plus(ttl).isAfter(Instant.now())) {
            return cached.result();
        }
        if (!checkObjects) {
            return ChainCheck.run(records, signer, mapper, Map.of(), null);
        }
        Map<String, String> keys = new HashMap<>();
        for (CheckpointView view : views.findByRentalIdOrderByIdAsc(rentalId)) {
            for (PhotoRef p : view.photos()) {
                keys.put(p.photoId(), p.objectKey());
            }
        }
        try {
            ChainCheck.Result result = ChainCheck.run(records, signer, mapper, keys, photos::currentSha256);
            cache.put(rentalId, new Cached(result, Instant.now()));
            if (!result.valid()) {
                log.warn("체인 검증 실패: rentalId={} seq={} {}", rentalId, result.brokenSeq(), result.reason());
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("저장소에 닿지 못해 체인 계산만으로 검증합니다: rentalId={} ({})", rentalId, e.toString());
            return ChainCheck.run(records, signer, mapper, Map.of(), null);
        }
    }

    public void invalidate(String rentalId) {
        cache.remove(rentalId);
    }
}
