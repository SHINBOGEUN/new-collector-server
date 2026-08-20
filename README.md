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
