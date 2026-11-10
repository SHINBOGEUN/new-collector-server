# GPU·공통 MQTT 수집

## 데이터 흐름

GPU 에이전트 → 공통 MQTT 구독 → GPU 전용 처리 → InfluxDB.
ChirpStack → 기존 LoRa 동적 소스 구독 → 기존 LoRa 처리 → InfluxDB.
두 처리 모두 장비 식별·모델 매핑은 Manager 공통 MQTT 조회 API를 사용한다.
MariaDB 직접 접속이나 장비/포인트 자동 INSERT는 하지 않는다.
실제 숫자 측정값은 `dcim_sensor`에 저장한다. identity와 포인트 설정은 Manager DB에서 관리한다.

## 확정 JSON

```json
{
  "time": "2026-10-14T08:11:13.545533Z",
  "deviceInfo": {
    "identityType": "MAC",
    "externalId": "9c:6b:00:9b:4b:d7",
    "deviceName": "9c6b009b4bd7"
  },
  "object": {"load_avg_1min": 0.01, "memory_used_GB": 1.24},
  "metadata": {"cpu": "AMD Ryzen 5 5500GT"},
  "components": [
    {
      "type": "GPU", "index": 0, "uuid": "GPU-AAA",
      "object": {"gpu_temperature": 59, "power": 249.8, "vram_temperature": null},
      "metadata": {"name": "NVIDIA GeForce RTX 3080"}
    }
  ]
}
```

`time`은 시간대가 포함된 ISO-8601 측정 시각이어야 한다.

## Manager에서 먼저 설정할 정보

1. GPU 서버를 등록하고 모델의 지원 프로토콜을 MQTT로 설정한다.
2. `device_mqtt_identity`에서 MAC과 내부 `device_id`를 연결한다.
3. `device_model_mqtt_point`에 모델별 포인트를 정의한다.

| point_name | payload_field | component_scope | component_type | component_path | component_index_field | component_uuid_field |
|---|---|---|---|---|---|---|
| load_avg_1min | object.load_avg_1min | DEVICE | NULL | 빈 문자열 | NULL | NULL |
| memory_used_GB | object.memory_used_GB | DEVICE | NULL | 빈 문자열 | NULL | NULL |
| gpu_temperature | object.gpu_temperature | COMPONENT | GPU | components | index | uuid |
| power | object.power | COMPONENT | GPU | components | index | uuid |

위 내용은 예시이며 SQL을 자동 실행하지 않는다. 카테고리·단위·DATA_POINT_TYPE은 Manager에서 설정한다.

## Influx schema

- 공통 태그: device_id, point_name, protocol=mqtt, model_id.
- Manager에서 조회되면 device_type, location_code도 기존 규칙에 맞춰 추가한다.
- COMPONENT에만 component_type, component_index, component_uuid 태그를 추가한다.
- GPU 숫자 필드는 value(double), 측정 시각은 time에서 나노초 정밀도로 저장한다.
- LoRa는 기존대로 수신 시각과 밀리초 정밀도를 유지하고 component 태그를 추가하지 않는다.
- MAC, scope, deviceName은 추가 태그로 저장하지 않는다.
- GPU 개수별 정의는 불필요하다. 같은 포인트를 components 배열 전체에 적용한다.
- 교체 전후 그래프는 device_id·component_type·component_index·point_name으로 묶는다.
  UUID가 태그이므로 Flux에서 UUID별 그룹을 제거하고 합쳐 조회해야 한다.

## 운영

기존 collector (`dcim/sensor/data`) 및 PUE 처리는 유지한다.
GPU는 새 에이전트 처리 경로를 사용한다. ChirpStack은 기존 LoRa 처리 경로를 사용하며
deviceInfo.devEui(없는 경우 deviceName)로 식별하고 object.*를 읽는다.
작업 시작 main에 없던 LoRa 구현은 origin/feat/dragino-standard-telemetry에서 복원했다.
LoRa·GPU 브로커·토픽은 Manager의 공통 MQTT 소스 설정만 사용한다.
자격증명은 호환성을 위해 sensor.mqtt.lora.credentials에서 credentialKey로 읽는다(GPU도 같은 키 저장소 사용).
공통 Paho 구독은 collector·PUE 전용이다. GPU 고정 토픽 구독은 제거했다.
LoRa sentinel(327.67/409.5) 제외, 값 매핑·배율, 오류 보고, 수신 시각 저장은 기존 로직을 유지한다.
소스 조회·상태 보고는 /api/manager/mqtt/sources 경로를 사용한다.
제거된 override API는 호출하지 않고 모델별 공통 MQTT 포인트를 사용한다.
Manager에 오류 적재 API가 없으므로 오류는 sensor-data 로그에 남긴다. raw payload는 오류 기록기가 출력하지 않는다.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| MQTT_CONFIG_TTL_MS | 60000 | 설정 갱신 주기 (ms) |
| MANAGER_SERVICE_URL | http://localhost:8080 | Manager |
| MANAGER_API_KEY | sensor-data-service | Manager API 키 |
| MANAGER_LOOKUP_ENABLED | true | 설정 조회 활성화 |

MQTT_AGENT_TOPIC은 더 이상 사용하지 않는다. Manager에서 예를 들어 다음 소스를 등록한다.

| sourceType | brokerUrl (예시) | topic |
|---|---|---|
| CHIRPSTACK_LORA | tcp://192.168.0.199:1883 | /application/# |
| AGENT | tcp://192.168.0.199:1883 | net/vivans/gpu-mon/# |

실제 브로커 주소로 바꿔 등록한다. 구독 여부는 소스의 enabled가 결정한다.
장비 식별값이 아직 없어도 소스를 구독하지만 미등록 장비의 값은 저장하지 않는다.
같은 브로커에서 소스 토픽이 서로 겹치지 않게 등록한다. 같은 토픽 안에서 JSON 형식을 자동 판별하는 기능은 없다.
소스 삭제·비활성화는 해당 연결을 끊고, configVersion 변경은 기존 연결을 새 설정으로 교체한다.
최초 연결 실패는 다음 소스 동기화에서 재시도하고, 연결 후 끊김은 Paho 자동 재접속으로 복구한다.
MQTT_TOPIC, MQTT_PUE_TOPIC, MQTT_BROKER_URL, INFLUX_*는 기존과 동일하다.

사용 API:
- GET /api/manager/mqtt/identities/bulk
- GET /api/manager/mqtt/mappings/models/bulk
- GET /api/manager/mqtt/identities/resolve?identityType=MAC&externalId=...
- GET /api/manager/mqtt/sources/bulk
- POST /api/manager/mqtt/sources/{id}/status

POST /api/internal/mqtt/sources/refresh는 Manager 소스 목록을 즉시 다시 읽는다.
기존 POST /api/internal/lora/sources/refresh는 같은 동작의 호환 별칭이다.
소스 목록 조회 실패·잘못된 응답은 기존 구독을 유지하고 다음 동기화에서 재시도한다.

캐시는 첫 메시지에서 읽고 주기적으로 전체 교체한다. 캐시에 없는 식별값은 resolve로 확인한다.
미등록 식별값도 갱신 시점까지 최대 1024개 캐시하여 반복 조회를 제한한다.
POST /api/internal/mqtt/config/refresh로 즉시 갱신할 수 있다. 기존 Sensor Data X-Api-Key 인증을 사용한다.
Manager의 기존 알림 URL인 POST /api/internal/lora/config/refresh는 기존 LoRa 캐시·소스 갱신 후 GPU 캐시도 갱신한다.
갱신 실패 시 만료된 설정으로 잘못된 장비에 저장하지 않고 해당 메시지를 저장하지 않는다.
영속 큐/재전송 처리는 없으므로 Manager 장애 중 저장하지 못한 메시지는 재생되지 않는다.

GPU는 null, N/A, NaN, Infinity, 미정의 값을 저장하지 않는다. 0은 유효한 값으로 저장한다.
GPU value_map을 지정하면 매핑된 값에 scale을 적용한다. 문자열 P2 등은 숫자 매핑이 필요하다.
LoRa 변환은 복원한 LoraValueConverter의 기존 규칙을 그대로 따른다.
GPU UUID를 읽도록 정의했는데 수신되지 않으면 저장하지 않는다. 중복 index는 덮어쓰기를 피하고 해당 슬롯을 제외한다.
metadata는 이번에 저장하지 않는다. CPU·GPU 이름 등 장비 정보의 저장 방식은 별도 결정이 필요하다.
배포 전 Manager와 함께 API를 전환하고 MQTT→실제 Influx 저장을 확인해야 한다.

## 검증 및 실행 준비

Java 17에서 `mvn test` 또는 `mvn package`로 검증한다. 테스트는 manager HTTP 응답을
모의 서버로, MQTT 수신은 메시지 핸들러로, Influx 쓰기는 mock API로 검증한다.
실제 브로커·DB에 접속하는 통합 테스트는 자동 수행하지 않는다.

실행 전 MANAGER_SERVICE_URL, MANAGER_API_KEY와 collector·PUE용 MQTT_BROKER_URL, MQTT_CLIENT_ID,
INFLUX_ENABLED=true 및 INFLUX_URL/TOKEN/ORG/BUCKET을 실제 환경에 맞게 설정한다.
컨테이너에서 localhost는 자기 컨테이너를 의미하므로 서비스 DNS 이름을 사용한다.
배포 환경은 SPRING_PROFILES_ACTIVE=prod 등 명시적 프로필을 권장한다.
Manager 포인트 목록이 비어 있으면 수신하더라도 저장할 값이 없으므로 먼저 등록해야 한다.
