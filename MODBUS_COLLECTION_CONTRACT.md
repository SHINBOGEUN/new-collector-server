# Modbus 정기 수집 연동 계약

Manager, Collector, Sensor Data의 Modbus 정기 수집 연동에 사용하는 단일 계약이다. 현재 구현 상태를 기준으로 정했으며, 각 서비스의 실제 연결은 후속 단계에서 구현한다. SNMP, PUE, LoRa의 기존 메시지와 저장 경로는 유지한다.

## 1. Manager → Collector: 수집 spec

Manager는 수집 그룹마다 `protocol: "modbus"`인 JSON을 생성한다. Collector의 `ModbusCollectionGroupSpec`, `ModbusCollectionTargetSpec`, `CollectionGroupModbusPointSpec`이 수신 형식의 기준이다. Manager의 현재 `CollectionGroupSpec`은 SNMP 전용 필드를 가진 별도 record이므로 Modbus를 위해 그대로 재사용하지 않는다. 공통 작업 필드와 프로토콜별 본문을 구분하되, 프로토콜별 point 변환 규칙은 한 곳에서 관리한다.

```json
{
  "taskId": 2,
  "groupId": 6,
  "modelId": 10,
  "protocol": "modbus",
  "cronExpression": "0 */1 * * * *",
  "timeoutMs": 2000,
  "retries": 1,
  "maxConcurrency": 10,
  "points": [],
  "targets": [
    {
      "deviceId": 101,
      "host": "192.0.2.10",
      "port": 502,
      "unitId": 1,
      "points": [
        {
          "name": "POWER",
          "registerType": "HOLDING",
          "address": 100,
          "dataType": "FLOAT32",
          "byteOrder": "CDAB",
          "scale": 1.0
        }
      ]
    }
  ],
  "skipped": []
}
```

- `groupId`는 수집 주기 그룹, `modelId`는 수집원 장비의 모델이다. 그룹에 연결된 장비는 수집원이며, `target.deviceId`는 **값을 저장할 장비 ID**다. `device_modbus_reading.target_device_id`가 수집원과 다를 수 있다. 이때 수집원의 모델을 검증하되 저장 대상의 모델이 달라도 그 이유만으로 제외하지 않는다.
- `host`/`port`는 수집원 장비의 활성 Modbus endpoint에서 가져온다. `unitId`는 회선 매핑이 있으면 `device_modbus_reading.unit_id`, 고정 주소 point면 `device_endpoint_modbus.unit_id`를 쓴다. 후자가 비어 있으면 임의의 Unit ID를 넣지 말고 해당 수집 대상을 제외하고 `skipped`에 이유를 기록한다.
- 하나의 물리적 endpoint에 여러 Unit ID가 연결되면 Unit ID별로 target을 나눈다. 같은 저장 대상 장비가 여러 target에 나타날 수 있지만 point 이름은 서로 달라야 한다. 같은 host/port에 여러 저장 대상 장비가 연결돼도 각 target의 `deviceId`를 유지한다.
- `address`는 실제 Modbus 요청의 **0 기반 주소**다. 장비 설명서의 4xxxx/3xxxx 표기에서 register type과 시작 주소를 구분해 Manager 설정에 저장한다. Collector는 주소를 다시 보정하지 않는다.
- `points`는 그룹 공통 목록 또는 target 전용 목록이다. Collector의 `ModbusPointResolver`는 `target.points != null`이면 그것만 사용한다. 빈 배열도 공통 목록을 덮어쓴다. Manager는 회선 매핑을 포함한 대상에는 최종 point 목록을 `target.points`로 완전히 펼쳐 보내고, 공통 목록과 의도치 않게 혼합하지 않는다. 같은 저장 대상 장비에 동일한 point 이름을 두 번 보낼 수 없다.
- 활성 모델 point, 활성 endpoint, 활성 reading만 보낸다. 저장 대상 장비의 활성 여부와 수집원 장비의 활성 여부도 확인한다. 제외 사유는 `skipped`에 남긴다. 수집 대상이 없는 그룹은 Collector에 실행 가능한 작업으로 등록하지 않는다.

### Point 값의 의미

| 필드 | 계약 |
| --- | --- |
| `registerType` | `COIL`, `DISCRETE`, `HOLDING`, `INPUT`; 각각 FC 01, 02, 03, 04 |
| `dataType` | `INT16`, `UINT16`은 1 register, `INT32`, `UINT32`, `FLOAT32`는 2 registers |
| `byteOrder` | 32비트는 `ABCD`, `CDAB`, `BADC`, `DCBA` 필수. 16비트는 Manager가 `null`을 보내고 Collector가 `AB`로 해석한다. Collector의 `BA`는 Manager 모델 enum에 없으므로 현 단계에서 Manager 설정값으로 제공하지 않는다. |
| `scale` | `null`이면 1.0. 디코딩한 수치에 곱한 결과를 저장한다. |
| `COIL`/`DISCRETE` | Collector 현행 검증기는 `dataType`을 요구하지만 비트 읽기에서는 사용하지 않는다. Manager는 `UINT16`, `byteOrder: null`을 보내고 결과는 숫자 `0` 또는 `1`로 내보낸다. |

레지스터 내부의 특정 비트를 추출하거나 짝수 비트만 선택하는 설정은 현재 Manager 모델과 Collector spec에 없다. 이런 point가 필요한 실제 장비는 `bitIndex` 또는 비트 마스크의 의미와 범위를 별도로 확정한 뒤 세부 단계에서 계약을 확장한다. 임의로 16비트 전체 값을 비트값처럼 저장하지 않는다.

## 2. Collector → Sensor Data: MQTT 메시지

기존 정기 수집 토픽 `dcim/sensor/data`와 `data` 구조를 사용한다. 최상위에 `protocol`만 추가해 저장 프로토콜을 전달한다. `type`은 기존 값 `schedule`을 유지한다. 숫자는 유한한 값만 포함하고, 수집에 실패한 point는 값으로 꾸며 넣지 않는다.

```json
{
  "datetime": "2026-09-30 12:34:56",
  "type": "schedule",
  "protocol": "modbus",
  "data": {
    "101": {
      "POWER": 1250.5,
      "RUNNING": 1
    }
  }
}
```

`data`의 key는 앞 절의 **저장 대상 장비 ID**다. 한 물리적 endpoint의 값을 여러 장비에 매핑했다면 각 장비 ID로 별도 메시지를 발행해도 된다. Collector의 MQTT 발행 형식 생성과 프로토콜 지정은 공통 경로 한 곳에서 처리한다.

기존 SNMP 발행 메시지에는 `protocol`이 없다. Sensor Data는 필드가 없을 때에만 `snmp`로 해석하고, `modbus`가 명시된 메시지는 `modbus`로 저장한다. 알 수 없는 프로토콜 값은 조용히 `snmp`로 바꾸지 않는다. LoRa는 별도 수신 경로에서 계속 `mqtt`로 저장한다.

## 3. Sensor Data → InfluxDB

기존 measurement와 narrow schema를 유지한다. 각 point에 `device_id=<저장 대상 장비 ID>`, `point_name=<point 이름>`, `protocol=modbus`, `value=<숫자>`를 기록한다. 기존 `SensorInfluxPointMapper`의 protocol 인자와 `InfluxWriteService.writeSensorPoints(..., component, protocol)`을 재사용한다. 단위는 기존처럼 Manager의 point 정의에서 조회하며 MQTT 메시지에 중복 적재하지 않는다.

## 4. 단계별 구현 책임과 연동 순서

1. **Manager**: Modbus spec 생성과 `CollectorSyncService`의 SNMP 전용 동기화 조건 확장. 수집원 장비, 회선 매핑, 저장 대상 장비의 관계를 spec에 반영한다.
2. **Sensor Data**: 정기 수집 payload의 선택적 `protocol` 필드 처리 및 Influx tag 전달. 기존 SNMP 메시지의 기본값을 검증한다.
3. **Collector**: Modbus TCP 읽기/디코딩/scale, 스케줄러 분기와 공통 MQTT 발행 경로 연결. 이 변경으로 `protocol=modbus` 메시지가 발행되기 전에 Sensor Data의 저장 경로를 먼저 적용한다.
4. **통합 확인**: 동일 endpoint의 복수 Unit ID, 다른 저장 대상 장비 ID, 16/32비트 값, 통신/변환 실패, MQTT와 InfluxDB의 최종 태그를 확인한다.

각 서비스의 내부 DTO와 엔티티는 해당 서비스에서 유지한다. 서비스 사이에 동일한 변환기나 MQTT serializer를 별도로 복사하지 않는다. 계약 변경 시 이 문서를 먼저 갱신하고 세 서비스의 구현을 함께 점검한다.
