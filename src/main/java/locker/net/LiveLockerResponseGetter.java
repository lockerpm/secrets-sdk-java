package locker.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import locker.LockerConfiguration;
import locker.exception.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class LiveLockerResponseGetter implements LockerResponseGetter {
    private final LockerResponseGetterOptions options;
    private Gson gson;

    public LiveLockerResponseGetter() {
        this(null);
    }

    public LiveLockerResponseGetter(LockerResponseGetterOptions options) {
        this.options = options;
        this.gson = new Gson();
    }

    private LockerRequest toLockerRequest(CliRequest apiRequest) throws LockerError {
        LockerRequest request = new LockerRequest(apiRequest.getMethod(), apiRequest.getCli(), apiRequest.getParams(), RequestOptions.merge(this.options, apiRequest.getOptions()));
        return request;
    }


    @Override
    public <T> T request(CliResource.RequestMethod method, List<String> cli, Map<String, Object> params, Type typeToken, RequestOptions options) throws LockerError {
        return null;
    }

    @Override
    public <T> T request(CliRequest apiRequest, Type typeToken) throws LockerError {
        String raw = "";
        LockerRequest request = toLockerRequest(apiRequest);
        RequestOptions options = request.getOptions();
        String accessKeyId = options.getAccessKeyId();
        String secretAccessKey = options.getSecretAccessKey();
        String apiBase = options.getApiBase();
        Boolean isJson = RequestOptions.getIsJsonFromType(typeToken);
        List<String> cli = apiRequest.getCli();
        LockerConfiguration config = LockerConfiguration.getInstance();
        String binaryFilePath = config.getBinaryFilePath();
        cli.add(0, binaryFilePath);
        if (accessKeyId != null && !accessKeyId.isEmpty()) {
            cli.add("--access-key-id");
            cli.add(accessKeyId);
        }
        if (secretAccessKey != null && !secretAccessKey.isEmpty()) {
            cli.add("--secret-access-key");
            cli.add(secretAccessKey);
        }
        String defaultUserAgent = "Java - " + config.getSdkVersion();
        cli.add("--agent");
        cli.add(defaultUserAgent);
        if (apiBase != null && !apiBase.isEmpty()) {
            cli.add("--api-base");
            cli.add(apiBase);
        }

        if (isJson) {
            cli.add("--json");
        }

        Map<String, String> headers = options.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            StringBuilder headersBuilder = new StringBuilder();
            for (Map.Entry<String, String> pair : options.getHeaders().entrySet()) {
                headersBuilder.append(pair.getKey()).append(":").append(pair.getValue()).append(",");
            }
            String headerStr = headersBuilder.toString();
            if (!headerStr.isEmpty()) {
                cli.add("--headers");
                cli.add(headerStr);
            }
        }


        ProcessBuilder processBuilder = new ProcessBuilder();

        String[] optionsArray = cli.toArray(new String[0]);
        System.out.println("optionsArray: " + String.join(" ", optionsArray));
        processBuilder.command(optionsArray);
        // Redirect the standard output
        processBuilder.redirectErrorStream(true);

        Process process = null;
        T resource = null;
        try {
            process = processBuilder.start();

            // Read the output incrementally using a BufferedReader
            try (InputStream inputStream = process.getInputStream();
                 InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                 BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {

                String line;
                StringBuilder outputBuilder = new StringBuilder();
                while ((line = bufferedReader.readLine()) != null) {
                    outputBuilder.append(line).append(System.lineSeparator());
                }
                int exitCode = process.waitFor();
                // Process the output as needed
                String output = outputBuilder.toString();
                if (process.exitValue() == 0) {
                    raw = output;
                } else {
                    List<String> signs = List.of("\"success\": false", "\"success\": true", "\"object\": \"error\"");
                    System.out.println(output);

                    boolean isContainSign = false;
                    for (String sign : signs) {
                        if (output.contains(sign)) {
                            raw = output;
                            isContainSign = true;
                            break;
                        }
                    }

                    if (!isContainSign) {
                        CliRunError exc = new CliRunError(output);
                        throw exc;
                    }
                }

                raw = interpretResponse(raw);
                if (!isJson) {
                    return (T) raw;
                } else {
                    try {
                        resource = CliResource.deserializeLockerObject(raw, typeToken, this);
                    } catch (JsonSyntaxException e) {
                        e.printStackTrace();
                        throw new ApiConnectionError("Invalid object: " + raw);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return resource;
                }
            }


        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            CliRunError exc = new CliRunError(e.getMessage());
            throw exc;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

    }

    private String interpretResponse(String responseBody) throws LockerError {
        try {
            JsonObject responseObj = JsonParser.parseString(responseBody).getAsJsonObject();
            if (shouldHandleAsError(responseObj)) {
                responseObj.addProperty("object", "error");
                handleErrorResponse(responseObj);
            }
            return responseBody;
        } catch (JsonSyntaxException | IllegalStateException exception) {
            return responseBody;
        }

    }

    private boolean shouldHandleAsError(JsonObject responseObj) {
        try {
            String objectStr = responseObj.has("object") ? responseObj.get("object").getAsString() : "";
            String successStr = responseObj.has("success") ? responseObj.get("success").getAsString() : "";
            boolean successBool = responseObj.has("success") ? responseObj.get("success").getAsBoolean() : true;
            return objectStr.equals("error") || !successBool || successStr.equals("false");
        } catch (NullPointerException ex) {
            return false;
        }
    }

    private void handleErrorResponse(JsonObject responseBody) throws LockerError {
        LockerError error = specificCliError(responseBody);
        throw error;
    }

    private LockerError specificCliError(JsonObject errorData) {
        int statusCode = errorData.has("status_code") ? errorData.get("status_code").getAsInt() : -1;
        String errorCode = errorData.has("error") ? errorData.get("error").getAsString() : "_";
        String message = errorData.has("message") ? errorData.get("message").getAsString() : "";

        if (statusCode == 429 || errorCode.equals("rate_limit")) {
            return new RateLimitError(message, gson.toJson(errorData), "rate_limit");
        }

        if (statusCode == 403 || errorCode.equals("permission_denied")) {
            return new PermissionDeniedError(message, gson.toJson(errorData), "permission_denied");
        }

        if (statusCode == 401 || errorCode.equals("unauthorized") || errorCode.equals("invalid_secret_access_key")) {
            return new AuthenticationError(message, gson.toJson(errorData), errorCode);
        }

        if (statusCode == 404 || errorCode.equals("not_found")) {
            return new ResourceNotFoundError(message, gson.toJson(errorData), "permission_denied");
        }

        if (statusCode >= 500 || errorCode.equals("server_error")) {
            return new ApiServerError(message, gson.toJson(errorData), errorCode);
        }

        if (errorCode.equals("http_error")) {
            return new ApiConnectionError(gson.toJson(errorData));
        }

        return new ApiError(message, gson.toJson(errorData), errorCode);
    }
}
