# 대덕 전화분류 도우미 2.0 (통화 내용 완전 자동)

- 패키지 `kr.daedeok.calls`, 1.9와 같은 열쇠(ddk-calls.keystore)로 서명 → 1.9 위에 덮어 설치됨
- 버전 2.0 (versionCode 11), minSdk 31, targetSdk 34, compileSdk 36
- 빌드: GitHub `daedeok-sttest` 저장소의 Actions "도우미 2.0 APK 만들기" (.github/workflows/helper2.yml)

## 하는 일
1. 받은 전화가 끝나면(통화 기록 변경 → CallWatch) 그 통화를 줄에 넣음 (Auto.onCallLog)
2. 갤럭시 자동 녹음 파일(Recordings/Call)을 이름 속 시각·길이·번호로 찾음 (Auto.findRecording)
3. 폰 안 음성인식(ML Kit GenAI, 고급→기본 자동 선택, 파일 통째로)으로 글자 (Auto.transcribe)
4. 정산유틸이 넘겨 준 서버 주소·도우미 열쇠(S.srv/S.htok)로 서버 v15 `talkIn`에 글자만 보냄 → 서버가 AI 요약
5. 서버 연결이 없으면 정산유틸 주소(#calls=…&talk=…)로 폰 안에서 직접 넘김 (1.9 방식)

## 뒤에서 막힐 때
폰 안 AI는 "화면에 떠 있는 앱"만 쓸 수 있을 수 있음(BACKGROUND_USE_BLOCKED).
- 다른 앱 위에 표시 허용 시: 작은 정리 창(AutoActivity)을 스스로 띄워 처리하고 닫힘
- 아니면: "통화 내용 정리하기" 알림 → 한 번 누르면 처리
- 한 번 막히면 3일 동안은 뒤에서 시도하지 않고 바로 정리 창으로 감

## 화면
- 앱 아이콘 길게 누르기 → "통화 내용 자동 정리" (TalkActivity): 상태·권한·최근 목록·지금 정리하기·진단 기록 복사
- `ddkcalls://status` 로도 열림
