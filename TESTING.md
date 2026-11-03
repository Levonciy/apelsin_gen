# Тестовые векторы и проверка реализации

## 1. Эталон

[reference/fixtures/vectors.json](reference/fixtures/vectors.json) содержит только синтетические данные. Приватный тестовый скаляр d=1 публично известен и не связан с нашим активным банковским ключом. TEST-ONLY-private.pem нельзя использовать как ключ реального генератора.

В vectors.json:

| Поле | Проверка |
|---|---|
| profile + utc | Вход для QrCodec |
| payment_message | Полное ожидаемое сообщение QR |
| bank_message | Полное ожидаемое сообщение банковского fingerprint |
| payment_sha256 | SHA-256 исходного UTF-8 сообщения |
| signature_raw_hex | Зафиксированная корректная подпись r || s |
| signature_der_hex | Та же подпись в DER |
| payment_qr | Готовый envelope с этой подписью |
| public_sec1_hex / public_spki_hex | Две формы одного публичного ключа |
| seed_upper_hex / x5id / sdk_public_string | Входы расчёта AppUserUUID |
| model_vectors | iPhone, Xiaomi и ветка fallback на 5-байтный BLAKE2b |
| der_vectors / invalid_der | Leading zero, positive sign, короткие integers, невалидные DER |
| sdk_copy_vectors | Копирование координат SDK со знаковым нулём; искусственная точка не является ключом для криптоопераций |

TEST-ONLY-expected-qr.png закодирован ZXing; его распознанное содержимое равно payment_qr. Pixel equality с Core Image не требуется: корректные QR могут отличаться mask/version/ECC при одинаковом payload.

Новые ECDSA-подписи не обязаны равняться signature_raw_hex. Равенство проверять только для конвертации уже фиксированной подписи. При новом подписании проверять сообщение, длину, верификацию и соответствие ключа.

## 2. Common tests

Файл [QrProtocolTest.kt](reference/commonTest/QrProtocolTest.kt) перенести в shared/src/commonTest/kotlin/qr/protocol. Он использует kotlin.test и KotlinCryptoBlake2b из commonMain.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("org.kotlincrypto.hash:blake2:0.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

Задачи Gradle зависят от имён targets в проекте. При наличии обычных jvm/iosSimulatorArm64 targets это обычно jvmTest и iosSimulatorArm64Test. Проверь их через ./gradlew tasks и запускай общий набор на обеих платформах, а не только JVM.

Обязательные случаи:

1. paymentMessage и bankMessage побайтно совпадают с JSON.
2. Разделители, порядок полей и регистр hex не изменяются при переносе.
3. Время не зависит от timezone/locale; границы года и leap day обрабатываются корректно.
4. BLAKE2b действительно параметризован на 4/5/16 байт.
5. Подписи DER ↔ P1363 конвертируются с сохранением r/s.
6. Негативные integers, лишние данные, non-minimal DER, нулевые/out-of-range scalars отклоняются.
7. Невалидный timestamp, пустой key_id, разделитель внутри поля, неверный user_app_id отклоняются через QrProtocolException.

Reference валидация ограничена наблюдаемым форматом: 16 ASCII-цифр карты и 10 цифр телефона. Поддержку других типов карт/номеров вводить отдельно по новым данным.

## 3. Криптографическая совместимость

На iOS:

```swift
let publicKey = try P256.Signing.PublicKey(derRepresentation: spkiData)
let signature = try P256.Signing.ECDSASignature(rawRepresentation: raw64)
XCTAssertTrue(publicKey.isValidSignature(signature, for: Data(message.utf8)))
```

Отрицательная проверка: добавить один символ в message и убедиться, что верификация вернула false. Не проверять только round-trip собственной функции: фиксированная подпись в vectors.json создана OpenSSL и должна приниматься CryptoKit / Android JCA.

Обратная проверка: сформировать подпись Swift, сохранить её derRepresentation и проверить OpenSSL:

```sh
openssl dgst -sha256 -verify TEST-ONLY-public.pem \
  -signature swift-signature.der message.txt
```

message.txt содержит ровно UTF-8 message без завершающего перевода строки. Файлы с PEM и DER не нужно переводить в текстовый hex перед этой командой.

На Android: X509EncodedKeySpec(SPKI DER) → KeyFactory EC → Signature SHA256withECDSA; для проверки перевести raw64 из вектора в DER. Исходный message подавать без предварительного хеширования.

## 4. Изображение и SwiftUI

- Отрисовать payload через QRRenderer на реальном iPhone и в симуляторе.
- Распознать результат независимым декодером; строка должна побайтно совпасть с подписанной.
- Проверить тёмную тему, маленький экран, Dynamic Type и поворот.
- Белая рамка не обрезается, модули не размываются, QR не накрыт декоративными элементами.
- Повторное открытие/foreground создаёт новый timestamp.
- Фоновая генерация не публикует устаревший кадр после ухода с экрана или смены привязки.
- Ошибка Keychain не оставляет старый QR с новым временем и не создаёт новый ключ.
- Нет текста о неизвестном серверном TTL. UI показывает время создания.

В SwiftUI reference используется revision для инвалидирования старых async-результатов. Это не заменяет тесты сценария смены аккаунта в host app.

## 5. Хранилище и onboarding

Проверить отдельно на устройстве:

- Импорт правильного PEM и восстановление того же публичного ключа после перезапуска.
- Отказ при несоответствии PEM/SPKI и при попытке перезаписать alias.
- Недоступный/заблокированный Keychain, отсутствие metadata при сохранившемся ключе, прерванный staging/commit.
- HTTP 200 + null на auth/confirm, тайм-аут после отправки, истёкший OTP, 401, 403 HTML, error_code 409036.
- Восстановление фазы после перезапуска с той же парой ключей.
- Неизвестные серверные статусы и явно appInstanceRevoked.
- Запрос новой регистрации происходит только после пользовательского действия.

Тесты сетевых DTO и state machine делать через fake API. Реальные OTP и ротацию аккаунта не вызывать в CI. В нашем опыте новая регистрация отзывала прежние привязки.

## 6. Что проверено при подготовке этого пакета

| Компонент | Результат и граница |
|---|---|
| QrProtocol / DerCodec / IdentityDerivation | Компилированы Kotlin/JVM 2.0.21; прогнан отдельный JVM harness |
| Message/UUID/model vectors | Совпадение с эталонным Python-прототипом; BLAKE2b в harness предоставлялся независимым Python hashlib adapter |
| P1363/DER и подпись | JVM JCA принимает OpenSSL-вектор, отвергает изменённое сообщение |
| CryptoKit | Импорт тестового PKCS#8 PEM и SPKI/SEC1, верификация OpenSSL-подписи, отказ после изменения message, подпись raw64 и DER проверены на macOS |
| Подпись CryptoKit → OpenSSL | Verified OK |
| UTC formatter Swift | Совпадение фиксированного Unix timestamp с UTC14 |
| P256Signer, QRRenderer, PaymentQrScreen | Swift 6 typecheck с iPhoneSimulator 27.1 SDK, deployment target iOS 17 |
| KotlinCryptoBlake2b | API конструктора/digest проверен по первичному исходнику библиотеки; зависимость не собиралась в KMP/Native здесь |
| KmpQrSource | Полная ветка import Shared не компилировалась: Shared.framework ещё не создан |
| Core Image renderer runtime | В CLI-окружении macOS createCGImage вернул nil; typecheck прошёл, но end-to-end отрисовку надо подтвердить на iOS |
| Приложенный PNG | ZXing encoder/decoder round-trip прошёл; это не runtime-проверка Core Image |
| Keychain / iOS UI runtime | Нужны проверки в подписанном host app; реальная связка приложения не запускалась |
| Реальная касса и оплата | Не проверялись |

Полный Gradle-проект, Kotlin/Native bridge и end-to-end iOS-приложение не входят в проведённую проверку. Этот пакет даёт спецификацию, reference-код и тесты, по которым можно собирать и проверять реализацию в целевом проекте.
