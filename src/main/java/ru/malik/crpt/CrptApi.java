package ru.malik.crpt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// Основной класс для взаимодействия с API Честного Знака.
// Отвечает за аутентификацию, ограничение запросов и создание документов.
public class CrptApi {
    // HTTP-клиент для отправки запросов к API
    private final HttpClient httpClient;
    // Объект для сериализации/десериализации JSON
    private final ObjectMapper objectMapper;
    // Базовый URL API
    private final String apiUrl = "https://ismp.crpt.ru/api/v3";
    private final RateLimiter rateLimiter;
    private volatile String authToken;
    private final ReentrantLock authLock = new ReentrantLock();

    // Конструктор класса
    // @param timeUnit Единица времени для ограничения запросов
    // @param requestLimit Максимальное количество запросов в заданный интервал
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.httpClient = HttpClient.newBuilder().build();
        this.objectMapper = new ObjectMapper();
        this.rateLimiter = new RateLimiter(timeUnit, requestLimit);
    }

    // Создает документ ввода товаров в оборот
    // @param document Данные документа
    // @param signature Подпись УКЭП (заглушка в текущей реализации)
    // @throws Exception В случае ошибок API или превышения лимита
    public void createDocument(Document document, String signature) throws Exception {
        // Проверяем и ждем, если превышен лимит запросов
        rateLimiter.acquire();

        // Получаем действующий токен аутентификации
        String token = getValidAuthToken();

        // Сериализуем документ в JSON
        String jsonBody = objectMapper.writeValueAsString(document);

        // Формируем POST-запрос для создания документа
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(apiUrl + "/lk/documents/create"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + token)
                .header("Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Отправляем запрос
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Проверяем статус ответа
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202) {
            throw new ApiException("Не удалось создать документ. Статус: " + response.statusCode() + ", Тело: " + response.body());
        }
    }

    // Получает действующий токен, обновляя его при необходимости
    // @return Токен аутентификации
    private String getValidAuthToken() throws Exception {
        authLock.lock();
        try {
            if (authToken == null) {
                authToken = obtainAuthToken();
            }
            return authToken;
        } finally {
            authLock.unlock();
        }
    }

    // Выполняет процесс аутентификации (двухэтапный)
    // @return Новый токен аутентификации
    protected String obtainAuthToken() throws Exception {
        // Проверяем лимит запросов
        rateLimiter.acquire();

        // Запрашиваем пару uuid и data
        HttpRequest keyRequest = HttpRequest.newBuilder()
                .uri(new URI(apiUrl + "/auth/cert/key"))
                .GET()
                .build();

        HttpResponse<String> keyResponse = httpClient.send(keyRequest, HttpResponse.BodyHandlers.ofString());

        if (keyResponse.statusCode() != 200) {
            throw new ApiException("Не удалось получить ключ аутентификации. Статус: " + keyResponse.statusCode());
        }

        // Десериализуем ответ
        AuthKeyResponse authKey = objectMapper.readValue(keyResponse.body(), AuthKeyResponse.class);

        // Подписываем данные (ЗАГЛУШКА: вместо реальной УКЭП используется Base64)
        String signedData = signData(authKey.data);


        AuthTokenRequest tokenRequestBody = new AuthTokenRequest(authKey.uuid, signedData);
        String tokenJson = objectMapper.writeValueAsString(tokenRequestBody);

        // Отправляем запрос на получение токена
        HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(new URI(apiUrl + "/auth/cert/"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(tokenJson))
                .build();

        HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());

        if (tokenResponse.statusCode() != 200) {
            throw new ApiException("Не удалось получить токен. Статус: " + tokenResponse.statusCode());
        }

        // Десериализуем ответ
        AuthTokenResponse tokenResponseObj = objectMapper.readValue(tokenResponse.body(), AuthTokenResponse.class);
        return tokenResponseObj.token;
    }

    // Подписывает данные (ЗАГЛУШКА)
    // В реальной системе должна использоваться библиотека для УКЭП (например, CryptoPro)
    // @param data Данные для подписи
    // @return Подписанные данные в Base64 (заглушка)
    private String signData(String data) {
        if (data == null) {
            throw new IllegalArgumentException("Data for signing cannot be null");
        }
        return java.util.Base64.getEncoder().encodeToString(data.getBytes());
    }

    // Внутренний класс для ограничения количества запросов
    private static class RateLimiter {
        // Интервал времени для ограничения (в миллисекундах)
        private final long intervalMillis;
        // Максимальное количество запросов
        private final int requestLimit;
        // Счетчик запросов
        private final AtomicInteger requestCount = new AtomicInteger(0);
        // Начало текущего окна
        private volatile long windowStart;
        // Блокировка для синхронизации
        private final ReentrantLock lock = new ReentrantLock();

        // Конструктор
        // @param timeUnit Единица времени
        // @param requestLimit Максимальное количество запросов
        RateLimiter(TimeUnit timeUnit, int requestLimit) {
            if (requestLimit <= 0) {
                throw new IllegalArgumentException("Лимит запросов должен быть положительным");
            }
            this.requestLimit = requestLimit;
            this.intervalMillis = timeUnit.toMillis(1);
            this.windowStart = Instant.now().toEpochMilli();
        }

        // Проверяет и ожидает, если превышен лимит запросов
        void acquire() {
            lock.lock();
            try {
                long now = Instant.now().toEpochMilli();
                // Сбрасываем счетчик, если окно времени истекло
                if (now - windowStart >= intervalMillis) {
                    windowStart = now;
                    requestCount.set(0);
                }

                // Ждем, пока не освободится место для нового запроса
                while (requestCount.get() >= requestLimit) {
                    long sleepTime = windowStart + intervalMillis - now;
                    if (sleepTime > 0) {
                        lock.unlock();
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new ApiException("Прервано ожидание лимита запросов");
                        }
                        lock.lock();
                        now = Instant.now().toEpochMilli();
                        if (now - windowStart >= intervalMillis) {
                            windowStart = now;
                            requestCount.set(0);
                        }
                    }
                }
                requestCount.incrementAndGet();
            } finally {
                lock.unlock();
            }
        }
    }

    // Класс для представления документа ввода в оборот
    public static class Document {
        @JsonProperty("participant_inn")
        private String participantInn;
        @JsonProperty("production_date")
        private String productionDate;
        @JsonProperty("production_type")
        private String productionType = "LOCAL";
        private List<Product> products;

        public Document(String participantInn, String productionDate, List<Product> products) {
            this.participantInn = participantInn;
            this.productionDate = productionDate;
            this.products = products;
        }
    }

    // Класс для представления товара в документе
    public static class Product {
        @JsonProperty("certificate_document")
        private String certificateDocument;
        @JsonProperty("certificate_document_date")
        private String certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        private String certificateDocumentNumber;
        private String uit;

        public Product(String certificateDocument, String certificateDocumentDate, String certificateDocumentNumber, String uit) {
            this.certificateDocument = certificateDocument;
            this.certificateDocumentDate = certificateDocumentDate;
            this.certificateDocumentNumber = certificateDocumentNumber;
            this.uit = uit;
        }
    }

    // Класс для десериализации ответа на запрос ключа аутентификации
    private static class AuthKeyResponse {
        @JsonProperty("uuid")
        private String uuid;
        @JsonProperty("data")
        private String data;
    }

    // Класс для формирования тела запроса токена
    private static class AuthTokenRequest {
        @JsonProperty("uuid")
        private String uuid;
        @JsonProperty("data")
        private String data;

        public AuthTokenRequest(String uuid, String data) {
            this.uuid = uuid;
            this.data = data;
        }
    }

    // Класс для десериализации ответа с токеном
    private static class AuthTokenResponse {
        @JsonProperty("token")
        private String token;
        @JsonProperty("code")
        private String code;
        @JsonProperty("error_message")
        private String errorMessage;
        @JsonProperty("description")
        private String description;
    }

    // Исключение для ошибок API
    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }
    }
}