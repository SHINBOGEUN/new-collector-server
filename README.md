# new-collector-server

매니저가 내려준 수집 그룹 JSON spec을 메모리에 올리고, cron마다 장비를 병렬 SNMP 조회한 뒤 성공한 장비만 MQTT로 바로 보내는 수집 서버입니다. DB는 없습니다.

## 실행

- Java 17
- 기본 포트 `8081` (`new-manager-server`의 8080과 겹치지 않음)

```bash
mvn spring-boot:run
```

매니저 → 컬렉터 인증 헤더: `X-Api-Key: manager-server`  
(`COLLECTOR_API_KEY`로 변경 가능)

MQTT 브로커가 없으면 `MQTT_ENABLED=false`로 기동할 수 있습니다. 수집은 되지만 publish는 건너뜁니다.

## Job API

### SNMP 실행 역할 분리

- `JobService`: 작업 등록·스케줄·활성 상태 관리 (기존 동작 유지).
- `CollectionTickRunner`: 그룹 병렬 실행, tick/실시간 대상 중복 방지, 결과 집계.
- `SnmpCollectionRunner`: 장비 한 대의 OID 치환·SNMP 조회·배율 적용·MQTT 발행 및 장비 단위 성공/실패 지표 기록.
- `CollectionTargetResult`: 정기 수집의 성공 여부와 실패 사유 전달.

정기 수집과 실시간 수집의 JSON/API는 변경하지 않았습니다. 현재 실행 지원은 여전히 SNMP이며,
프로토콜별 검증 분리와 Modbus 지원은 후속 작업입니다.

| Method | Path |
|--------|------|
| POST | `/api/jobs/register` |
| PUT | `/api/jobs/{collectorJobId}` |
| DELETE | `/api/jobs/{collectorJobId}` |
| PATCH | `/api/jobs/{collectorJobId}/toggle` |
| GET | `/api/jobs` |
| GET | `/api/health` |

등록 본문은 매니저 `generated_spec`과 동일한 JSON입니다. 같은 `groupId`를 다시 등록하면 기존 job을 갱신합니다.

재시작 시 메모리 job은 사라지므로, 매니저가 활성 그룹을 다시 push해야 합니다.
