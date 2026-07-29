package locker.service;

import locker.exception.LockerError;
import locker.model.EnvironmentPage;
import locker.net.*;
import locker.param.environment.EnvironmentCreateParams;
import locker.param.environment.EnvironmentListParams;
import locker.param.environment.EnvironmentListPageParams;
import locker.param.environment.EnvironmentRetrieveParams;
import locker.param.environment.EnvironmentUpdateParams;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class EnvironmentService extends ApiService {
    public EnvironmentService(LockerResponseGetter responseGetter) {
        super(responseGetter);
    }

    public <T> T retrieve(String name, Class<T> typeToken) throws LockerError {
        return this.retrieve(name, null, null, typeToken);
    }

    public <T> T retrieve(String name, EnvironmentRetrieveParams params, Class<T> typeToken) throws LockerError {
        return this.retrieve(name, params, null, typeToken);
    }

    public <T> T retrieve(String name, RequestOptions options, Class<T> typeToken) throws LockerError {
        return this.retrieve(name, null, options, typeToken);
    }

    public <T> T retrieve(String name, EnvironmentRetrieveParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("environment");
        cli.add("get");
        cli.add("--name");
        cli.add(name);
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, typeToken);

    }

    public <T> T create(EnvironmentCreateParams params, Class<T> typeToken) throws LockerError {

        return this.create(params, null, typeToken);
    }

    public <T> T create(EnvironmentCreateParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("environment");
        cli.add("create");
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.POST,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, typeToken);
    }

    /**
     * Returns one bounded page of environments.
     */
    public EnvironmentPage listPage() throws LockerError {
        return listPage(null, null);
    }

    /**
     * Returns one bounded page of environments.
     */
    public EnvironmentPage listPage(
            EnvironmentListPageParams params
    ) throws LockerError {
        return listPage(params, null);
    }

    /**
     * Returns one bounded page of environments.
     */
    public EnvironmentPage listPage(
            EnvironmentListPageParams params,
            RequestOptions options
    ) throws LockerError {
        List<String> cli = new ArrayList<>();
        cli.add("environment");
        cli.add("list_page");
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );
        return this.call(request, EnvironmentPage.class);
    }

    public <T> T list(Class<T> typeToken) throws LockerError {
        return this.list(null, null, typeToken);
    }

    public <T> T list(EnvironmentListParams params, Class<T> typeToken) throws LockerError {
        return this.list(params, null, typeToken);
    }

    public <T> T list(EnvironmentListParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
        return this.list(params, options, (Type) typeToken);
    }

    public <T> T list(Type typeToken) throws LockerError {
        return this.list(null, null, typeToken);
    }

    public <T> T list(
            EnvironmentListParams params,
            RequestOptions options,
            Type typeToken
    ) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("environment");
        cli.add("list");
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                CliRequestParams.paramsToMap(params),
                options
        );

        return this.call(request, typeToken);
    }

    public <T> T modify(String name, Class<T> typeToken) throws LockerError {
        return this.modify(name, null, null, typeToken);

    }

    public <T> T modify(String name, EnvironmentUpdateParams params, Class<T> typeToken) throws LockerError {
        return this.modify(name, params, null, typeToken);
    }

    public <T> T modify(String name, RequestOptions requestOptions, Class<T> typeToken) throws LockerError {
        return this.modify(name, null, requestOptions, typeToken);
    }

    public <T> T modify(String name, EnvironmentUpdateParams params, RequestOptions requestOptions, Class<T> typeToken) throws LockerError {
        List<String> cli = new ArrayList<String>();
        cli.add("environment");
        cli.add("update");
        cli.add("--name");
        cli.add(name);
        if (params == null) {
            params = EnvironmentUpdateParams.builder().setName(name).build();
        }
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.UPDATE,
                cli,
                CliRequestParams.paramsToMap(params),
                requestOptions
        );
        return this.call(request, typeToken);
    }
}
