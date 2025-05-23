package ru.malik.crpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class CrptApiTest {
    private CrptApi crptApi;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    // Подготовка перед каждым тестом: инициализация моков и установка HttpClient через рефлексию
    @BeforeEach
    void setUp() throws Exception {
        httpClient = Mockito.mock(HttpClient.class);
        objectMapper = new ObjectMapper();
        crptApi = new CrptApi(TimeUnit.SECONDS, 2);
        // Используем рефлексию для установки замоканного HttpClient в CrptApi
        java.lang.reflect.Field httpClientField = CrptApi.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(crptApi, httpClient);
    }

    // Тест проверяет работу RateLimiter, ограничивающего количество запросов (2 запроса в секунду).
    // Ожидается, что выполнение 3 запросов займет не менее 1 секунды из-за ограничения.
    @Test
    void testRateLimiter() throws Exception {
        // Создаем моки для ответов API
        HttpResponse<String> keyResponse = mock(HttpResponse.class);
        when(keyResponse.statusCode()).thenReturn(200);
        when(keyResponse.body()).thenReturn("{\"uuid\":\"123\",\"data\":\"testData\"}");

        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"token\":\"testToken\"}");

        HttpResponse<String> docResponse = mock(HttpResponse.class);
        when(docResponse.statusCode()).thenReturn(200);
        when(docResponse.body()).thenReturn("{}");

        // Настраиваем мок HttpClient для последовательного возврата ответов
        doReturn(keyResponse).doReturn(tokenResponse).doReturn(docResponse)
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));

        // Замеряем время выполнения 3 запросов
        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            crptApi.createDocument(createSampleDocument(), "fakeSignature");
        }
        long duration = System.currentTimeMillis() - start;
        // Проверяем, что выполнение заняло не менее 1 секунды из-за RateLimiter
        assertTrue(duration >= 1000, "Rate limiter should enforce delay");
    }

    // Тест проверяет успешное создание документа через API.
    // Убеждаемся, что метод createDocument выполняется без ошибок и делает 3 HTTP-запроса (ключ, токен, документ).
    @Test
    void testSuccessfulDocumentCreation() throws Exception {
        // Создаем моки для ответов API
        HttpResponse<String> keyResponse = mock(HttpResponse.class);
        when(keyResponse.statusCode()).thenReturn(200);
        when(keyResponse.body()).thenReturn("{\"uuid\":\"123\",\"data\":\"testData\"}");

        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"token\":\"testToken\"}");

        HttpResponse<String> docResponse = mock(HttpResponse.class);
        when(docResponse.statusCode()).thenReturn(200);
        when(docResponse.body()).thenReturn("{}");

        // Настраиваем мок HttpClient для последовательного возврата ответов
        doReturn(keyResponse).doReturn(tokenResponse).doReturn(docResponse)
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));

        // Создаем тестовый документ и вызываем метод
        CrptApi.Document document = createSampleDocument();
        crptApi.createDocument(document, "fakeSignature");

        // Проверяем, что HttpClient вызывался 3 раза (для ключа, токена и документа)
        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
    }

    // Ожидается, что метод createDocument выбросит ApiException.
    @Test
    void testFailedDocumentCreation() throws Exception {
        // Создаем моки для ответов API
        HttpResponse<String> keyResponse = mock(HttpResponse.class);
        when(keyResponse.statusCode()).thenReturn(200);
        when(keyResponse.body()).thenReturn("{\"uuid\":\"123\",\"data\":\"testData\"}");

        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"token\":\"testToken\"}");

        HttpResponse<String> docResponse = mock(HttpResponse.class);
        when(docResponse.statusCode()).thenReturn(400);
        when(docResponse.body()).thenReturn("{\"error_message\":\"Bad Request\"}");

        // Настраиваем мок HttpClient для последовательного возврата ответов
        doReturn(keyResponse).doReturn(tokenResponse).doReturn(docResponse)
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));

        // Создаем тестовый документ и проверяем, что метод выбросит исключение
        CrptApi.Document document = createSampleDocument();
        assertThrows(CrptApi.ApiException.class, () -> crptApi.createDocument(document, "fakeSignature"));
    }

    // Тест проверяет потокобезопасность CrptApi при многопоточном создании документов.
    // Запускает 5 потоков, каждый из которых создает документ, и проверяет, что API обрабатывает запросы корректно.
    @Test
    void testConcurrentAccess() throws Exception {
        // Создаем моки для ответов API
        HttpResponse<String> keyResponse = mock(HttpResponse.class);
        when(keyResponse.statusCode()).thenReturn(200);
        when(keyResponse.body()).thenReturn("{\"uuid\":\"123\",\"data\":\"testData\"}");

        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"token\":\"testToken\"}");

        HttpResponse<String> docResponse = mock(HttpResponse.class);
        when(docResponse.statusCode()).thenReturn(200);
        when(docResponse.body()).thenReturn("{}");

        doReturn(keyResponse).doReturn(tokenResponse).doReturn(docResponse)
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));

        // Запускаем 5 потоков для создания документов
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    crptApi.createDocument(createSampleDocument(), "fakeSignature");
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        // Проверяем, что HttpClient вызывался как минимум threadCount раз
        verify(httpClient, atLeast(threadCount)).send(any(HttpRequest.class), any());
    }

    // Вспомогательный метод для создания тестового документа
    private CrptApi.Document createSampleDocument() {
        List<CrptApi.Product> products = List.of(
                new CrptApi.Product("CONFORMITY_CERTIFICATE", "2023-01-01", "12345", "0104650117240408211dmfcZNcM")
        );
        return new CrptApi.Document("1234567890", "2023-01-01", products);
    }
}