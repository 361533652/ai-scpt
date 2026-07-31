package com.gmscpt.agent.impl;

import com.gmscpt.agent.LlmClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible API client.
 * Works with OpenAI, DeepSeek, Moonshot, or any OpenAI-compatible endpoint.
 */
public class OpenAiClient implements LlmClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final Gson GSON = new Gson();
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final OkHttpClient httpClient;

    public OpenAiClient(String apiKey, String modelName, String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = (modelName == null || modelName.isBlank()) ? "gpt-4o-mini" : modelName;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String chat(List<ChatMessage> messages) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelName);

        JsonArray messagesArray = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());
            msgObj.addProperty("content", msg.content());
            messagesArray.add(msgObj);
        }
        requestBody.add("messages", messagesArray);

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        String requestJson = GSON.toJson(requestBody);

        // 调试日志：打印请求
        System.out.println("====== AI REQUEST ======");
        System.out.println("URL: " + url);
        System.out.println("Model: " + modelName);
        System.out.println("Body: " + requestJson);
        System.out.println("========================");

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "no body";
                System.err.println("====== AI ERROR " + response.code() + " ======");
                System.err.println(body);
                throw new RuntimeException("API error " + response.code() + ": " + body);
            }

            String responseBody = response.body().string();

            // 调试日志：打印响应
            System.out.println("====== AI RESPONSE ======");
            System.out.println(responseBody);
            System.out.println("=========================");

            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                return message.get("content").getAsString();
            }
            throw new RuntimeException("No response content from API");
        }
    }

    @Override
    public String chatStream(List<ChatMessage> messages, StreamCallback callback) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelName);
        requestBody.addProperty("stream", true);

        JsonArray messagesArray = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());
            msgObj.addProperty("content", msg.content());
            messagesArray.add(msgObj);
        }
        requestBody.add("messages", messagesArray);

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        String requestJson = GSON.toJson(requestBody);

        System.out.println("====== AI STREAM REQUEST ======");
        System.out.println("URL: " + url);
        System.out.println("Model: " + modelName);
        System.out.println("===============================");

        // 流式专用客户端：readTimeout=0 表示不超时（SSE长连接）
        OkHttpClient streamClient = httpClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .build();

        try (Response response = streamClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "no body";
                System.err.println("====== AI STREAM ERROR " + response.code() + " ======");
                System.err.println(body);
                throw new RuntimeException("API error " + response.code() + ": " + body);
            }

            StringBuilder fullContent = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;

                    try {
                        JsonObject json = GSON.fromJson(data, JsonObject.class);
                        JsonArray choices = json.getAsJsonArray("choices");
                        if (choices != null && choices.size() > 0) {
                            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                            if (delta == null) continue;

                            // 思考阶段: reasoning_content
                            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                callback.onThinking(delta.get("reasoning_content").getAsString());
                            }

                            // 正式回复: content（跳过 null）
                            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                String chunk = delta.get("content").getAsString();
                                fullContent.append(chunk);
                                callback.onContent(chunk);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[STREAM] Parse error: " + e.getMessage());
                    }
                }
            }

            System.out.println("====== AI STREAM DONE (" + fullContent.length() + " chars) ======");
            return fullContent.toString();
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException("连接超时，请检查网络和接口地址", e);
        } catch (java.net.ConnectException e) {
            throw new RuntimeException("无法连接服务器: " + e.getMessage(), e);
        }
    }

    @Override
    public String ping() throws Exception {
        // 优先尝试 /models（无需AI推理，最快），短超时
        OkHttpClient fastClient = httpClient.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        Request modelsReq = new Request.Builder()
                .url(modelsUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response resp = fastClient.newCall(modelsReq).execute()) {
            if (resp.isSuccessful()) {
                return "OK";
            }
            // 401/403 认证失败，直接报错
            if (resp.code() == 401 || resp.code() == 403) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new RuntimeException("API error " + resp.code() + ": " + body);
            }
            // 其他错误码(404/405等) 降级到 chat/completions
        } catch (java.io.IOException e) {
            // 超时或网络异常，也降级到 chat/completions
        }

        // 降级：最小化 chat/completions 请求
        OkHttpClient chatClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelName);
        requestBody.addProperty("max_tokens", 1);

        JsonArray messagesArray = new JsonArray();
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", "user");
        msgObj.addProperty("content", "hi");
        messagesArray.add(msgObj);
        requestBody.add("messages", messagesArray);

        String chatUrl = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        Request chatReq = new Request.Builder()
                .url(chatUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(GSON.toJson(requestBody), JSON_TYPE))
                .build();

        try (Response resp = chatClient.newCall(chatReq).execute()) {
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new RuntimeException("API error " + resp.code() + ": " + body);
            }
            return "OK";
        }
    }

    @Override
    public String getProviderName() {
        return "OpenAI Compatible";
    }
}
