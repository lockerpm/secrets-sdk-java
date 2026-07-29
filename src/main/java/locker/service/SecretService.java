package locker.service;

import locker.exception.LockerError;
import locker.model.SecretPage;
import locker.net.*;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretListParams;
import locker.param.secret.SecretListPageParams;
import locker.param.secret.SecretRetrieveParams;
import locker.param.secret.SecretUpdateParams;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;


public class SecretService extends ApiService {
    public SecretService(LockerResponseGetter responseGetter) {
        super(responseGetter);
    }


    public <T> T retrieve(String name, Class<T> typeToken) throws LockerError {
        return this.retrieve(name, null, null, typeToken);
    }


    public <T> T retrieve(String name, SecretRetrieveParams params, Class<T> typeToken) throws LockerError {
        return this.retrieve(name, params, null, typeToken);
    }

    public <T> T retrieve(String name, RequestOptions options, Class<T> typeToken) throws LockerError {
        return this.retrieve(name, null, options, typeToken);
    }

    public <T> T retrieve(String name, SecretRetrieveParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("secret");
        cli.add("get");
        cli.add("--key");
        cli.add(name);
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, typeToken);

    }

    public <T> T retrieve(String name, Class<T> typeToken, String envName) throws LockerError {
        return this.retrieve(name, null, null, typeToken, envName);
    }

    public <T> T retrieve(String name, SecretRetrieveParams params, Class<T> typeToken, String envName) throws LockerError {
        return this.retrieve(name, params, null, typeToken, envName);
    }

    public <T> T retrieve(String name, RequestOptions options, Class<T> typeToken, String envName) throws LockerError {
        return this.retrieve(name, null, options, typeToken, envName);
    }

    public <T> T retrieve(String name, SecretRetrieveParams params, RequestOptions options, Class<T> typeToken, String envName) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("secret");
        cli.add("get");
        cli.add("--key");
        cli.add(name);
        cli.add("--environment");
        cli.add(envName);
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, typeToken);
    }

    public <T> T create(SecretCreateParams params, Class<T> typeToken) throws LockerError {

        return this.create(params, null, typeToken);
    }

    public <T> T create(SecretCreateParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("secret");
        cli.add("create");

        CliRequest request = new CliRequest(
                CliResource.RequestMethod.POST,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, typeToken);
    }

    public <T> T list(Class<T> typeToken) throws LockerError {
        return this.list(null, null, typeToken);
    }

    public <T> T list(SecretListParams params, Class<T> typeToken) throws LockerError {
        return this.list(params, null, typeToken);
    }

    public <T> T list(
            String environmentName,
            Class<T> typeToken
    ) throws LockerError {
        return this.list(
                SecretListParams.builder()
                        .setEnvironmentName(environmentName)
                        .build(),
                null,
                typeToken
        );
    }

    public <T> T list(SecretListParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
        return this.list(params, options, (Type) typeToken);
    }

    public <T> T list(Type typeToken) throws LockerError {
        return this.list(null, null, typeToken);
    }

    public <T> T list(
            SecretListParams params,
            RequestOptions options,
            Type typeToken
    ) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("secret");
        cli.add("list");
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );

        return this.call(request, typeToken);
    }

    /**
     * Returns one bounded page of secrets.
     */
    public SecretPage listPage() throws LockerError {
        return listPage(null, null);
    }

    /**
     * Returns one bounded page of secrets.
     */
    public SecretPage listPage(
            SecretListPageParams params
    ) throws LockerError {
        return listPage(params, null);
    }

    /**
     * Returns one bounded page of secrets.
     */
    public SecretPage listPage(
            SecretListPageParams params,
            RequestOptions options
    ) throws LockerError {
        List<String> cli = new ArrayList<>();
        cli.add("secret");
        cli.add("list_page");
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, SecretPage.class);
    }

    public <T> T modify(String name, Class<T> typeToken) throws LockerError {
        return this.modify(name, null, null, typeToken);

    }

    public <T> T modify(String name, SecretUpdateParams params, Class<T> typeToken) throws LockerError {
        return this.modify(name, params, null, typeToken);
    }

    public <T> T modify(String name, RequestOptions requestOptions, Class<T> typeToken) throws LockerError {
        return this.modify(name, null, requestOptions, typeToken);
    }

    public <T> T modify(String name, SecretUpdateParams params, RequestOptions requestOptions, Class<T> typeToken) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("secret");
        cli.add("update");
        cli.add("--key");
        cli.add(name);
        SecretUpdateParams effectiveParams = params;
        if (effectiveParams == null) {
            effectiveParams = SecretUpdateParams
                    .builder().
                    setKey(name)
                    .build();
        }

        CliRequest request = new CliRequest(
                CliResource.RequestMethod.UPDATE,
                cli,
                CliRequestParams.paramsToMap(effectiveParams),
                requestOptions
        );
        return this.call(request, typeToken);
    }

    public <T> T modify(String name, Class<T> typeToken, String envName) throws LockerError {
        return this.modify(name, null, null, typeToken, envName);

    }

    public <T> T modify(String name, SecretUpdateParams params, Class<T> typeToken, String envName) throws LockerError {
        return this.modify(name, params, null, typeToken, envName);
    }

    public <T> T modify(String name, RequestOptions requestOptions, Class<T> typeToken, String envName) throws LockerError {
        return this.modify(name, null, requestOptions, typeToken, envName);
    }

    public <T> T modify(String name, SecretUpdateParams params, RequestOptions requestOptions, Class<T> typeToken, String envName) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("secret");
        cli.add("update");
        cli.add("--key");
        cli.add(name);
        cli.add("--environment");
        cli.add(envName);

        SecretUpdateParams effectiveParams = params == null
                ? SecretUpdateParams.builder().setKey(name).build()
                : params;

        CliRequest request = new CliRequest(
                CliResource.RequestMethod.UPDATE,
                cli,
                CliRequestParams.paramsToMap(effectiveParams),
                requestOptions
        );
        return this.call(request, typeToken);
    }

}
