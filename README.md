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

### 정기 수집 spec의 공통 계약과 프로토콜별 DTO

공통 인터페이스는 `module.job.domain`에 두고, SNMP 전용 DTO/OID는 `domain.snmp`, Modbus 전용 DTO/enum/point resolver는 `domain.modbus`에 둡니다. 하위 패키지의 구현을 허용하도록 공통 인터페이스는 일반 interface를 사용합니다. Live/PUE 클래스의 위치와 동작은 유지합니다.

- `CollectionGroupSpec`: 작업 ID, cron, timeout, targets 등 공통 정보의 인터페이스. `JobService`와 API는 이 타입을 사용합니다.
- `SnmpCollectionGroupSpec` / `SnmpCollectionTargetSpec`: `community`, `oids`, `instanceId`를 사용하는 SNMP 전용 record입니다.
- `ModbusCollectionGroupSpec` / `ModbusCollectionTargetSpec`: 공통·전용 `points`와 `unitId`를 사용하는 Modbus 전용 record입니다.
- `CollectionGroupTargetSpec`: 결과 저장 대상 `deviceId`와 접속 정보 `host`/`port`의 공통 인터페이스입니다.
- JSON의 `protocol`은 `snmp` 또는 `modbus`로 지정합니다. Jackson이 전용 DTO를 선택하며, 누락·알 수 없는 값은 400으로 거부합니다. 기존 소문자 `snmp` JSON의 필드 구조는 유지합니다.
- `ModbusPointResolver`: target points가 null/생략이면 그룹 points를 사용합니다. target points가 있으면 그것만 사용하며, 빈 배열도 그대로 유지합니다. 둘 다 없으면 빈 목록을 반환합니다.

현재 Modbus는 DTO 역직렬화와 공통 job 등록·수정 경로만 준비되어 있습니다. Modbus 전용 설정 검증·실제 통신·값 변환은 아직 구현하지 않았고, tick은 Modbus를 SNMP 실행기로 보내지 않습니다. 실시간/PUE spec 구조는 이번 분리 대상이 아닙니다.

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
