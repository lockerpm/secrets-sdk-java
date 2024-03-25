package locker.service;

import locker.exception.LockerError;
import locker.net.*;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretListParams;
import locker.param.secret.SecretRetrieveParams;
import locker.param.secret.SecretUpdateParams;

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
    cli.add("--name");
    cli.add(name);
    cli.add("--env");
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

  public <T> T list(SecretListParams params, RequestOptions options, Class<T> typeToken) throws LockerError {
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
    cli.add("--name");
    cli.add(name);
    String newKey = params.getKey();
    if (newKey == null || newKey.isEmpty()) {
      params.setKey(name);
    }
    CliRequest request = new CliRequest(
      CliResource.RequestMethod.UPDATE,
      cli,
      CliRequestParams.paramsToMap(params),
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
    cli.add("--name");
    cli.add(name);
    cli.add("--env");
    cli.add(envName);
    String newKey = params.getKey();
    if (newKey == null || newKey.isEmpty()) {
      params.setKey(name);
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
