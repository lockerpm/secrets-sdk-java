# Locker Secret Java client library

<p align="center">
  <img src="https://cystack.net/images/logo-black.svg" alt="CyStack" width="50%"/>
</p>

[![Maven Central](https://img.shields.io/badge/maven--central-v24.16.0-blue)](https://mvnrepository.com/artifact/com.stripe/stripe-java)

The official [Locker][locker] Java client library.

---

The Locker Secret Java Client library provides convenient access to the Locker Secret API from applications written in
the
Java language. It includes a pre-defined set of classes for API resources that initialize themselves dynamically
from API responses which makes it compatible with a wide range of versions of the Locker Secret API.

## The Developer - CyStack

The Locker Secret Java Client Library is developed by CyStack, one of the leading cybersecurity companies in Vietnam.
CyStack is a member of Vietnam Information Security Association (VNISA) and Vietnam Association of CyberSecurity
Product Development. CyStack is a partner providing security solutions and services for many large domestic and
international enterprises.

CyStack’s research has been featured at the world’s top security events such as BlackHat USA (USA),
BlackHat Asia (Singapore), T2Fi (Finland), XCon - XFocus (China)... CyStack experts have been honored by global
corporations such as Microsoft, Dell, Deloitte, D-link...

## Documentation

The documentation will be updated later.

## Requirements

- Java 1.8 or later

## Installation

### Gradle users

Add this dependency to your project's build file:

```groovy
implementation 'io.locker:lockerpm:0.0.2'
```

### Maven users

Add this dependency to your project's POM:

```xml

<dependency>
    <groupId>io.locker</groupId>
    <artifactId>lockerpm</artifactId>
    <version>0.0.2</version>
</dependency>
```

### Others

You'll need to manually install the following JARs:

- [The LockerPM JAR](https://search.maven.org/remotecontent?filepath=com/stripe/stripe-java/24.16.0/stripe-java-24.16.0.jar)
- [Google Gson][gson] from <https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar>.

## Usage

### Configuration access key

The SDK needs to be configured with your access key id and your secret access key, which is available in your Locker
Secret Dashboard. These keys must not be disclosed.These keys must not be disclosed. If you reveal these keys, you need
to revoke them immediately. Environment variables are a good solution and they are easy to consume in most programming
languages.

#### Set up credentials on Linux/MacOS

```shell
export ACCESS_KEY_ID=<YOUR_ACCESS_KEY_ID>
export SECRET_ACCESS_KEY=<YOUR_SECRET_ACCESS_KEY>
```

#### Set up credentials on Windows

Powershell

```shell
$Env:ACCESS_KEY_ID = '<YOUR_ACCESS_KEY_ID>'
$Env:SECRET_ACCESS_KEY = '<SECRET_ACCESS_KEY>'
```

Command Prompt

```shell
set ACCESS_KEY_ID=<YOUR_ACCESS_KEY_ID>
set SECRET_ACCESS_KEY=<YOUR_SECRET_ACCESS_KEY>
```

You also need to set `api_base` value (default is `https://api.locker.io/locker_secrets`).
If you need to set your custom headers, you also need to set `headers` value in the `options` param:

```java
Map<String, String> headers = new HashMap<String, String>() {
    {
        put("CF-Access-Client-Id", "YOUR_CF_ACCESS_CLIENT_ID");
        put("CF-Access-Client-Secret", "YOUR_CF_ACCESS_CLIENT_SECRET");
    }
};
LockerResponseGetterOptions responseGetter = new LockerClient.LockerClientBuilder().setApiBase(
        "YOUR_API_BASE"
).setHeaders(headers).buildOptions();
LockerClient client = new LockerClient(new LiveLockerResponseGetter(responseGetter));

```

You can also pass parameters or use the shared credential file (~/.locker/credentials), but we do
not recommend these ways.

```java
import locker.LockerClient;
import locker.exception.LockerError;

import locker.model.Secret;
import locker.param.secret.SecretRetrieveParams;

public class LockerExample {
    public static void main(String[] args) {
        LockerClient client = new LockerClient("YOUR_ACCESS_KEY_ID", "YOUR_ACCESS_KEY_SECRET");
        SecretRetrieveParams params = new SecretRetrieveParams();

        try {
            Secret secret = client.secrets().retrieve("YOUR_SECRET_KEY", Secret.class);
            System.out.println(secret);

        } catch (LockerError e) {
            e.printStackTrace();
        }
    }
}
```

See the project's [functional tests][functional-tests] for more examples.

### Per-request Configuration

All of the request methods accept an optional `RequestOptions` object. This is
used if you want to set access key id, secret access key or headers on each method

```java
RequestOptions requestOptions = RequestOptions.builder()
        .setAccessKeyId("access_key_id")
        .setSecretAccessKey("secret_access_key")
        .build();
Secret secret = client.secrets().retrieve("java_key_1", requestOptions, Secret.class);
```

### Per-request Object type

The Java SDK can return objects of two types for each request: either a String or a LockerObject.

```java
// return Secret type
Secret secret = client.secrets().retrieve("java_key_1", requestOptions, Secret.class);
// return String
String secretValue = client.secrets().retrieve("java_key_1", requestOptions, String.class);
```

Now, you can use SDK to get or set values:

### List secrets

```java
String secrets = client.secrets().list(String.class);

/**
 *  return a list of secrets
 * */
Class<? super LockerCollection<Secret>> type = new TypeToken<LockerCollection<Secret>>() {
}.getRawType();
LockerCollection<Secret> secretList = (LockerCollection<Secret>) client.secrets().list(type);
```

### Get a secret by secret key

```java
// Get a secret by secret key
Secret secret = client.secrets().retrieve("java_key_1", Secret.class);
String secretValue = client.secrets().retrieve("java_key_1", String.class);

// Get a secret by secret key and specific environment name
Secret secret = client.secrets().retrieve("java_key_1", Secret.class, "env_name");
String secret = client.secrets().retrieve("java_key_1", String.class, "env_name");
```

### Create new secret

```java
SecretCreateParams createParams = new SecretCreateParams.Builder()
        .setKey("key")
        .setValue("value")
        .setDescription("description")
        .build();
Secret newSecret = client.secrets().create(createParams, Secret.class);
```

### Update a secret

```java
SecretUpdateParams updateParams = new SecretUpdateParams.Builder()
        .setKey("your_update_secret_key")
        .setValue("your_update_secret_value")
        .setDescription("your_update_secret_description")
        .build();

// Update a secret by secret key
Secret updatedSecret = client.secrets().modify("your_secret_key", updateParams, Secret.class);

// Update a secret by secret key and specific environment name
Secret updatedSecret = client.secrets().modify("your_secret_key", updateParams, Secret.class, "your_secret_env_name");
```

### List environments

```java
Class<? super LockerCollection<Environment>> type = new TypeToken<LockerCollection<Environment>>() {
}.getRawType();
LockerCollection<Environment> listEnvs = (LockerCollection<Environment>) client.environments().list(type);

String envs = client.environments().list(String.class);
```

### Get an environment object by name

```java
Environment environment = client.environments().retrieve("your_env_name", Environment.class);

```

### Create new environment

```java
EnvironmentCreateParams createEnvParams = EnvironmentCreateParams.builder()
        .setName("your_env_name")
        .setExternalUrl("your_env_external_url")
        .setDescription("your_env_description")
        .build();
Environment newEnv = client.environments().create(createEnvParams, Environment.class);
```

### Update an environment by name

```java
EnvironmentUpdateParams params = EnvironmentUpdateParams.builder()
        .setName("your_update_env_name")
        .setExternalUrl("your_update_env_external_url")
        .setDescription("your_update_env_description")
        .build();
Environment updatedEnv = client.environments().modify("your_env_name", params, Environment.class);
```

### Error Handling

Locker Secret SDK offers some kinds of errors. They can reflect external events, like invalid credentials, network
interruptions, or code problems, like invalid API calls.

If an immediate problem prevents a function from continuing, the SDK raises an exception. It’s a best practice to catch
and handle exceptions. To catch an exception, use Java’s `try/catch` syntax. Catch `LockerError` or its
subclasses to handle Locker-specific exceptions only. Each subclass represents a different kind of exception. When you
catch an exception, you can use its class to choose a response.

Example:

```java

SecretCreateParams params = SecretCreateParams.builder()
  .setValue("your_secret_value")
  .setKey("your_secret_key")
  .setDescription("your_secret_description")
  .build();

try {
  Secret newSecret = client.secrets().create(params,Secret.class);
} catch (LockerError e) {
  e.printStackTrace();
}
```

In the SDK, error objects belong to LockerError and its subclasses. Use the documentation for each class
for advice about how to respond.

| Name                    | Class                 | Description                                                                                                                                        |
|-------------------------|-----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Authentication Error    | AuthenticationError   | Invalid `access_client_id` or `invalid secret_access_key`                                                                                          |
| Permission Denied Error | PermissionDeniedError | Your credential does not have enough permission to execute this operation                                                                          |
| RateLimit Error         | RateLimitError        | Too many requests                                                                                                                                  |
| API Error               | APIError              | You made an API call with the wrong parameters, in the wrong state, or in an invalid way or Something went wrong on Locker’s end (These are rare.) |
| CLI Run Error           | CliRunError           | The encryption/decryption binary runs errors by invalid local data, process interruptions, or invalid `secret_access_key`                          |

## Examples

See the project's [examples' folder][examples' folder] for more examples.

## Development

To run the tests:

```sh
mvn test
```

You can run particular tests by passing `--tests Class#method`. Make sure you
use the fully qualified class name. For example:

```sh
mvn -Dtest=locker.model.SecretTest test
```

The library uses [Project Lombok][lombok]. While it is not a requirement, you
might want to install a [plugin][lombok-plugins] for your favorite IDE to
facilitate development.


[functional-tests]: https://github.com/locker/locker-java/blob/master/src/test/java/locker/functional/

[examples' folder]: https://github.com/locker/locker-java/blob/master/src/test/java/locker/functional/

[gson]: https://github.com/google/gson


[lombok]: https://projectlombok.org

[lombok-plugins]: https://projectlombok.org/setup/overview

[proguard]: https://www.guardsquare.com/en/products/proguard

[spotless]: https://github.com/diffplug/spotless

[locker]: https://locker.com

<!--
# vim: set tw=79:
-->
