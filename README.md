# DCIM Collector Server

DCIM 수집 및 수집 데이터 처리를 담당하는 서버입니다.

- 수집 Job: Manager가 전달한 설정에 따라 장비 데이터를 수집합니다.
- MQTT 발행: 수집 결과를 MQTT로 전송합니다.
- Ingest: MQTT 메시지를 수신해 InfluxDB에 저장하고 관련 처리를 수행합니다.
- 요구 사항: JDK 17 및 로컬 실행에 필요한 연동 설정값.
- 실행 설정: 작업 폴더의 `config/application.yml`(Git 제외)에 로컬 설정을 입력합니다.
- 실행: Windows `./mvnw.cmd spring-boot:run`, Linux/macOS `./mvnw spring-boot:run`.
- 테스트: Windows `./mvnw.cmd verify`, Linux/macOS `./mvnw verify`.
- Swagger UI: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) (활성화된 경우).
- 기준 문서: [DCIM_NEW 문서](../docs/README.md).
- [Modbus 연동 계약](MODBUS_COLLECTION_CONTRACT.md).
